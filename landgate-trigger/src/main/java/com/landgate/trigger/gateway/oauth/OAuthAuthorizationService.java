package com.landgate.trigger.gateway.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.api.oauth.IOAuthService;
import com.landgate.api.oauth.dto.*;
import com.landgate.infrastructure.config.OAuthProperties;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.account.service.AccountDomainService;
import com.landgate.domain.account.service.CredentialDomainService;
import com.landgate.types.constant.RedisKeys;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.enums.Status;
import com.landgate.types.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * OAuth 授权服务 —— 管理 OAuth Authorization Code Flow + PKCE 流程。
 * <p>
 * 流程：生成 PKCE 参数 → 存储 state 到 Redis → 返回授权 URL →
 * 管理员浏览器授权 → 前端回调 → 用 code 换 token → 创建 Account。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthAuthorizationService implements IOAuthService {

    private final OAuthProperties oauthProperties;
    private final CredentialDomainService credentialService;
    private final AccountDomainService accountDomainService;
    private final OAuthTokenRefreshService tokenRefreshService;

    @Qualifier("redissonClient")
    private final RedissonClient redissonClient;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public OAuthAuthorizeResponse authorize(OAuthAuthorizeRequest request) {
        String platformKey = request.platform().toLowerCase();
        if ("openai".equals(platformKey)) {
            throw new BadRequestException("OpenAI uses Device Code Flow, please use POST /api/v1/admin/oauth/device-code instead");
        }
        OAuthProperties.ProviderConfig provider = oauthProperties.getProviders().get(platformKey);
        if (provider == null) {
            throw new BadRequestException("Unsupported OAuth platform: " + platformKey);
        }

        // Generate PKCE (encoding varies by platform: base64url for Anthropic, hex for OpenAI)
        String codeVerifier = generateCodeVerifier(provider.getPkceEncoding());
        String codeChallenge = generateCodeChallenge(codeVerifier);

        // Generate state
        String state = UUID.randomUUID().toString();

        // Store state → {codeVerifier, platform, redirectUri} in Redis
        String redirectUri = request.redirectUri() != null
                ? request.redirectUri()
                : provider.getRedirectUri() != null
                    ? provider.getRedirectUri()
                    : oauthProperties.getDefaultRedirectUri();

        storeState(state, codeVerifier, platformKey, redirectUri);

        // Build authorize URL
        String authorizeUrl = buildAuthorizeUrl(provider, codeChallenge, state, redirectUri);

        log.debug("OAuth authorize URL generated: platform={}", platformKey);

        return new OAuthAuthorizeResponse(
                authorizeUrl,
                state,
                oauthProperties.getStateExpireSeconds()
        );
    }

    @Override
    public OAuthCallbackResponse callback(OAuthCallbackRequest request) {
        // Validate and retrieve state from Redis
        StateData stateData = loadAndDeleteState(request.state());
        if (stateData == null) {
            throw new BadRequestException("Invalid or expired OAuth state");
        }

        String platformKey = stateData.platform;
        OAuthProperties.ProviderConfig provider = oauthProperties.getProviders().get(platformKey);
        if (provider == null) {
            throw new BadRequestException("Unsupported OAuth platform: " + platformKey);
        }

        log.debug("OAuth callback: platform={}", platformKey);

        try {
            // Exchange code for tokens (JSON for Anthropic, form-encoded for others)
            boolean useJson = OAuthHttpProfile.usesJsonTokenExchange(provider);
            String body;

            if (useJson) {
                ObjectNode tokenReq = JSON.createObjectNode();
                tokenReq.put("grant_type", "authorization_code");
                tokenReq.put("code", request.code());
                tokenReq.put("redirect_uri", stateData.redirectUri);
                tokenReq.put("code_verifier", stateData.codeVerifier);
                tokenReq.put("client_id", provider.getClientId());
                body = tokenReq.toString();
            } else {
                body = "grant_type=authorization_code"
                        + "&code=" + URLEncoder.encode(request.code(), StandardCharsets.UTF_8)
                        + "&redirect_uri=" + URLEncoder.encode(stateData.redirectUri, StandardCharsets.UTF_8)
                        + "&code_verifier=" + URLEncoder.encode(stateData.codeVerifier, StandardCharsets.UTF_8)
                        + "&client_id=" + URLEncoder.encode(provider.getClientId(), StandardCharsets.UTF_8);
            }

            var httpReqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(provider.getTokenUrl()))
                    .timeout(Duration.ofSeconds(15));
            OAuthHttpProfile.applyContentType(httpReqBuilder, OAuthHttpProfile.tokenExchangeContentType(provider));
            OAuthHttpProfile.applyProviderUserAgent(httpReqBuilder, provider);
            HttpRequest httpReq = httpReqBuilder.POST(HttpRequest.BodyPublishers.ofString(body)).build();

            HttpResponse<String> resp = HTTP.send(httpReq, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                log.error("OAuth token exchange failed: platform={}, status={}, body={}",
                        platformKey, resp.statusCode(), resp.body());
                throw new BadRequestException("OAuth token exchange failed: HTTP " + resp.statusCode());
            }

            var tokenResp = JSON.readTree(resp.body());
            String accessToken = tokenResp.has("access_token") ? tokenResp.get("access_token").asText() : null;
            String refreshToken = tokenResp.has("refresh_token") ? tokenResp.get("refresh_token").asText() : null;
            long expiresIn = tokenResp.has("expires_in") ? tokenResp.get("expires_in").asLong() : 3600;

            if (accessToken == null) {
                throw new BadRequestException("No access_token in OAuth response");
            }

            // Extract email for naming (Anthropic returns email_address, OpenAI uses id_token)
            String email = tokenResp.has("email_address") ? tokenResp.get("email_address").asText() : null;

            // Encrypt tokens
            String encryptedAccess = credentialService.encrypt(accessToken);
            String encryptedRefresh = refreshToken != null ? credentialService.encrypt(refreshToken) : null;

            // Build credentials JSON
            ObjectNode credsNode = JSON.createObjectNode();
            credsNode.put("access_token", encryptedAccess);
            if (encryptedRefresh != null) {
                credsNode.put("refresh_token", encryptedRefresh);
            }
            Instant expiresAt = Instant.now().plusSeconds(expiresIn);
            credsNode.put("token_expires_at", expiresAt.toString());
            credsNode.put("oauth_provider", platformKey);
            credsNode.put("token_encrypted", true);

            // Create Account entity
            Platform platform = Platform.from(platformKey);
            String accountName = email != null && !email.isEmpty()
                    ? platformKey + "-" + email
                    : "OAuth-" + platform.name() + "-" + System.currentTimeMillis();
            AccountEntity account = AccountEntity.builder()
                    .name(accountName)
                    .platform(platform)
                    .type(AccountType.OAUTH)
                    .credentials(credsNode.toString())
                    .extra("{}")
                    .concurrency(3)
                    .priority(50)
                    .status(Status.ACTIVE)
                    .schedulable(true)
                    .build();

            AccountEntity created = accountDomainService.create(account);

            // Schedule proactive refresh if refresh_token is available
            if (encryptedRefresh != null) {
                tokenRefreshService.scheduleProactiveRefresh(created.getId(), expiresAt);
            }

            log.info("OAuth account created: account_id={}, name={}, platform={}, expires_at={}",
                    created.getId(), created.getName(), platformKey, expiresAt);

            return new OAuthCallbackResponse(
                    created.getId(),
                    created.getName(),
                    created.getPlatform(),
                    created.getType(),
                    created.getStatus(),
                    expiresAt.toString()
            );

        } catch (Exception e) {
            if (e instanceof BadRequestException) throw (BadRequestException) e;
            log.error("OAuth callback error: platform={}", platformKey, e);
            throw new RuntimeException("OAuth token exchange error", e);
        }
    }

    @Override
    public void refreshToken(Long accountId) {
        AccountEntity account = accountDomainService.getById(accountId);
        if (account.getType() != AccountType.OAUTH) {
            throw new BadRequestException("Account is not OAuth type");
        }

        String newToken = tokenRefreshService.refreshAccessToken(accountId);
        if (newToken == null) {
            throw new BadRequestException("Token refresh failed");
        }

        log.info("Manual OAuth token refresh: account_id={}", accountId);
    }

    // ==================== Device Code Flow (OpenAI) ====================

    @Override
    public DeviceCodeResponse initiateDeviceCode(DeviceCodeRequest request) {
        String platformKey = request.platform().toLowerCase();
        if (!"openai".equals(platformKey)) {
            throw new BadRequestException("Device Code Flow is only supported for OpenAI");
        }

        OAuthProperties.ProviderConfig provider = oauthProperties.getProviders().get(platformKey);
        if (provider == null) {
            throw new BadRequestException("Unsupported OAuth platform: " + platformKey);
        }
        if (provider.getDeviceCodeUrl() == null) {
            throw new BadRequestException("Device Code Flow is not configured for platform: " + platformKey);
        }

        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("client_id", provider.getClientId());

            var httpReqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(provider.getDeviceCodeUrl()))
                    .timeout(Duration.ofSeconds(15));
            OAuthHttpProfile.applyJsonContentType(httpReqBuilder);
            OAuthHttpProfile.applyProviderUserAgent(httpReqBuilder, provider);
            HttpRequest httpReq = httpReqBuilder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();

            HttpResponse<String> resp = HTTP.send(httpReq, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                log.error("Device code request failed: status={}, body={}", resp.statusCode(), resp.body());
                throw new BadRequestException("Device code request failed: HTTP " + resp.statusCode());
            }

            JsonNode json = JSON.readTree(resp.body());
            String deviceAuthId = json.has("device_auth_id") ? json.get("device_auth_id").asText() : null;
            String userCode = json.has("user_code") ? json.get("user_code").asText() : null;
            int expiresIn = parseIntField(json, "expires_in", 900);
            int interval = parseIntField(json, "interval", 5);
            String verificationUri = provider.getDeviceVerificationUri() != null
                    ? provider.getDeviceVerificationUri()
                    : "https://auth.openai.com/codex/device";

            if (deviceAuthId == null || userCode == null) {
                throw new BadRequestException("Invalid device code response from upstream");
            }

            log.debug("Device code generated: platform={}", platformKey);

            return new DeviceCodeResponse(deviceAuthId, userCode, verificationUri, expiresIn, interval);

        } catch (Exception e) {
            if (e instanceof BadRequestException) throw (BadRequestException) e;
            log.error("Device code initiation error: platform={}", platformKey, e);
            throw new RuntimeException("Device code initiation error", e);
        }
    }

    @Override
    public DeviceCodePollResponse pollDeviceCode(DeviceCodePollRequest request) {
        String platformKey = "openai";
        OAuthProperties.ProviderConfig provider = oauthProperties.getProviders().get(platformKey);
        if (provider == null || provider.getDevicePollUrl() == null) {
            throw new BadRequestException("Device Code Flow is not configured for OpenAI");
        }

        try {
            // Step 1: Poll the device auth token endpoint
            ObjectNode pollBody = JSON.createObjectNode();
            pollBody.put("device_auth_id", request.deviceAuthId());
            pollBody.put("user_code", request.userCode());

            var pollReqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(provider.getDevicePollUrl()))
                    .timeout(Duration.ofSeconds(15));
            OAuthHttpProfile.applyJsonContentType(pollReqBuilder);
            OAuthHttpProfile.applyProviderUserAgent(pollReqBuilder, provider);
            HttpRequest pollReq = pollReqBuilder.POST(HttpRequest.BodyPublishers.ofString(pollBody.toString())).build();

            HttpResponse<String> pollResp = HTTP.send(pollReq, HttpResponse.BodyHandlers.ofString());

            if (pollResp.statusCode() == 403 || pollResp.statusCode() == 404) {
                return new DeviceCodePollResponse("PENDING", null);
            }
            if (pollResp.statusCode() == 410) {
                return new DeviceCodePollResponse("EXPIRED", null);
            }
            if (pollResp.statusCode() != 200) {
                log.error("Device code poll failed: status={}, body={}", pollResp.statusCode(), pollResp.body());
                throw new BadRequestException("Device code poll failed: HTTP " + pollResp.statusCode());
            }

            // Step 2: Parse the authorization_code and code_verifier from poll response
            JsonNode pollResult = JSON.readTree(pollResp.body());
            String authCode = pollResult.has("authorization_code") ? pollResult.get("authorization_code").asText() : null;
            String codeVerifier = pollResult.has("code_verifier") ? pollResult.get("code_verifier").asText() : null;

            if (authCode == null || codeVerifier == null) {
                log.error("Device poll response missing authorization_code or code_verifier: {}",
                        pollResp.body());
                throw new BadRequestException("Invalid device poll success response");
            }

            log.debug("Device code authorized, exchanging code for tokens");

            // Step 3: Exchange code for tokens at the OAuth token endpoint
            String deviceRedirectUri = provider.getDeviceRedirectUri() != null
                    ? provider.getDeviceRedirectUri()
                    : "https://auth.openai.com/deviceauth/callback";

            String tokenBody = "grant_type=authorization_code"
                    + "&code=" + URLEncoder.encode(authCode, StandardCharsets.UTF_8)
                    + "&redirect_uri=" + URLEncoder.encode(deviceRedirectUri, StandardCharsets.UTF_8)
                    + "&code_verifier=" + URLEncoder.encode(codeVerifier, StandardCharsets.UTF_8)
                    + "&client_id=" + URLEncoder.encode(provider.getClientId(), StandardCharsets.UTF_8);

            var tokenReqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(provider.getTokenUrl()))
                    .timeout(Duration.ofSeconds(15));
            OAuthHttpProfile.applyContentType(tokenReqBuilder, OAuthHttpProfile.tokenExchangeContentType(provider));
            OAuthHttpProfile.applyProviderUserAgent(tokenReqBuilder, provider);
            HttpRequest tokenReq = tokenReqBuilder.POST(
                    HttpRequest.BodyPublishers.ofString(tokenBody)).build();

            HttpResponse<String> tokenResp = HTTP.send(tokenReq, HttpResponse.BodyHandlers.ofString());

            if (tokenResp.statusCode() != 200) {
                log.error("Device code token exchange failed: status={}, body={}",
                        tokenResp.statusCode(), tokenResp.body());
                throw new BadRequestException("Token exchange failed: HTTP " + tokenResp.statusCode());
            }

            // Step 4: Encrypt tokens and create Account
            JsonNode tokenJson = JSON.readTree(tokenResp.body());
            String accessToken = tokenJson.has("access_token") ? tokenJson.get("access_token").asText() : null;
            String refreshToken = tokenJson.has("refresh_token") ? tokenJson.get("refresh_token").asText() : null;
            long expiresIn = tokenJson.has("expires_in") ? tokenJson.get("expires_in").asLong() : 3600;
            String idToken = tokenJson.has("id_token") ? tokenJson.get("id_token").asText() : null;

            if (accessToken == null) {
                throw new BadRequestException("No access_token in token response");
            }

            // Extract email from id_token JWT for account naming
            String email = extractEmailFromIdToken(idToken);

            String encryptedAccess = credentialService.encrypt(accessToken);
            String encryptedRefresh = refreshToken != null ? credentialService.encrypt(refreshToken) : null;

            ObjectNode credsNode = JSON.createObjectNode();
            credsNode.put("access_token", encryptedAccess);
            if (encryptedRefresh != null) {
                credsNode.put("refresh_token", encryptedRefresh);
            }
            Instant expiresAt = Instant.now().plusSeconds(expiresIn);
            credsNode.put("token_expires_at", expiresAt.toString());
            credsNode.put("oauth_provider", platformKey);
            credsNode.put("token_encrypted", true);

            String accountName = email != null && !email.isEmpty()
                    ? "openai-" + email
                    : "OAuth-" + Platform.from(platformKey).name() + "-" + System.currentTimeMillis();

            AccountEntity account = AccountEntity.builder()
                    .name(accountName)
                    .platform(Platform.from(platformKey))
                    .type(AccountType.OAUTH)
                    .credentials(credsNode.toString())
                    .extra("{}")
                    .concurrency(3)
                    .priority(50)
                    .status(Status.ACTIVE)
                    .schedulable(true)
                    .build();

            AccountEntity created = accountDomainService.create(account);

            if (encryptedRefresh != null) {
                tokenRefreshService.scheduleProactiveRefresh(created.getId(), expiresAt);
            }

            log.info("Device code OAuth account created: account_id={}, platform={}, expires_at={}",
                    created.getId(), platformKey, expiresAt);

            OAuthCallbackResponse callbackResponse = new OAuthCallbackResponse(
                    created.getId(),
                    created.getName(),
                    created.getPlatform(),
                    created.getType(),
                    created.getStatus(),
                    expiresAt.toString()
            );

            return new DeviceCodePollResponse("SUCCESS", callbackResponse);

        } catch (Exception e) {
            if (e instanceof BadRequestException) throw (BadRequestException) e;
            log.error("Device code poll error", e);
            throw new RuntimeException("Device code poll error", e);
        }
    }

    // ==================== PKCE ====================

    /**
     * 生成 PKCE code_verifier —— 64 字节随机数，按平台指定编码。
     * <p>
     * Anthropic 使用 Base64URL 编码，OpenAI（Codex CLI）使用 Hex 编码。
     *
     * @param pkceEncoding "base64url" 或 "hex"
     */
    private String generateCodeVerifier(String pkceEncoding) {
        byte[] bytes = new byte[64];
        SECURE_RANDOM.nextBytes(bytes);
        if ("hex".equalsIgnoreCase(pkceEncoding)) {
            return bytesToHex(bytes);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 生成 PKCE code_challenge —— code_verifier 的 SHA-256 哈希，Base64URL 编码（无 padding）。
     */
    private String generateCodeChallenge(String codeVerifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate code challenge", e);
        }
    }

    // ==================== Redis State Management ====================

    /**
     * 将 PKCE code_verifier 和平台信息存入 Redis，以 state 为 key。
     * 设置 TTL 防止 state 被无限占用（默认 5 分钟）。
     */
    private void storeState(String state, String codeVerifier, String platform, String redirectUri) {
        try {
            ObjectNode data = JSON.createObjectNode();
            data.put("code_verifier", codeVerifier);
            data.put("platform", platform);
            data.put("redirect_uri", redirectUri);
            RBucket<String> bucket = redissonClient.<String>getBucket(RedisKeys.oauthStateKey(state));
            bucket.set(data.toString(), oauthProperties.getStateExpireSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to store OAuth state: state={}", state, e);
            throw new RuntimeException("Failed to store OAuth state", e);
        }
    }

    /**
     * 从 Redis 读取并删除 OAuth state 数据，确保 state 只能被使用一次。
     *
     * @return state 关联的 StateData，不存在或已过期时返回 null
     */
    private StateData loadAndDeleteState(String state) {
        try {
            RBucket<String> bucket = redissonClient.<String>getBucket(RedisKeys.oauthStateKey(state));
            String json = bucket.getAndDelete();
            if (json == null) return null;
            var node = JSON.readTree(json);
            return new StateData(
                    node.get("code_verifier").asText(),
                    node.get("platform").asText(),
                    node.get("redirect_uri").asText()
            );
        } catch (Exception e) {
            log.error("Failed to load OAuth state: state={}", state, e);
            return null;
        }
    }

    // ==================== URL Builder ====================

    /**
     * 构造完整的 OAuth 授权 URL，包含 PKCE 参数（code_challenge + S256）、state 和平台特有参数。
     */
    private String buildAuthorizeUrl(OAuthProperties.ProviderConfig provider,
                                      String codeChallenge, String state, String redirectUri) {
        StringBuilder url = new StringBuilder(provider.getAuthorizeUrl())
                .append("?response_type=code")
                .append("&client_id=").append(URLEncoder.encode(provider.getClientId(), StandardCharsets.UTF_8))
                .append("&redirect_uri=").append(URLEncoder.encode(redirectUri, StandardCharsets.UTF_8))
                .append("&scope=").append(URLEncoder.encode(provider.getScopes(), StandardCharsets.UTF_8))
                .append("&code_challenge=").append(codeChallenge)
                .append("&code_challenge_method=S256")
                .append("&state=").append(state);

        // Platform-specific extra params (e.g., OpenAI codex_cli_simplified_flow=true)
        provider.getExtraAuthorizeParams().forEach((key, value) ->
                url.append("&").append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                        .append("=").append(URLEncoder.encode(value, StandardCharsets.UTF_8)));

        return url.toString();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 从 id_token JWT 中提取 email 声明。
     * 不验证签名，仅解码 payload 提取 email 字段。
     *
     * @param idToken JWT 字符串（header.payload.signature），为 null 时返回 null
     * @return email 声明值，不存在或解析失败返回 null
     */
    private String extractEmailFromIdToken(String idToken) {
        if (idToken == null || idToken.isEmpty()) return null;
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) return null;
            String payload = parts[1];
            // Add padding if needed
            int padding = 4 - payload.length() % 4;
            if (padding != 4) {
                payload += "=".repeat(padding);
            }
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            JsonNode claims = JSON.readTree(decoded);
            return claims.has("email") ? claims.get("email").asText() : null;
        } catch (Exception e) {
            log.warn("Failed to extract email from id_token", e);
            return null;
        }
    }

    /**
     * 从 JSON 节点中解析整数字段，兼容字符串和数字两种类型。
     * 当字段不存在或无法解析时返回默认值。
     */
    private int parseIntField(JsonNode node, String field, int defaultVal) {
        if (!node.has(field)) return defaultVal;
        JsonNode fieldNode = node.get(field);
        if (fieldNode.isNumber()) return fieldNode.asInt();
        if (fieldNode.isTextual()) {
            try { return Integer.parseInt(fieldNode.asText()); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }

    /** OAuth 授权流程的临时状态数据，存入 Redis 等待回调验证。 */
    private record StateData(String codeVerifier, String platform, String redirectUri) {}
}
