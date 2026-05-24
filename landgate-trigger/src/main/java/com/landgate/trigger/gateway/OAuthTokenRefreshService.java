package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.infrastructure.config.OAuthProperties;
import com.landgate.domain.account.adapter.repository.IAccountRepository;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.account.service.CredentialDomainService;
import com.landgate.types.constant.RedisKeys;
import com.landgate.types.enums.AccountType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * OAuth Token 刷新服务 —— 负责 access_token 的按需刷新和主动刷新调度。
 * <p>
 * 刷新策略：
 * <ul>
 *   <li>按需刷新 —— AbstractGatewayHandler 检测到 401 时调用 {@link #refreshAccessToken(Long)}</li>
 *   <li>主动刷新 —— {@code OAuthTokenRefreshScheduler} 通过 Redis Sorted Set 定期扫描即将过期的 token</li>
 * </ul>
 * 使用 Redisson RLock 防止同一账号并发刷新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthTokenRefreshService {

    private final OAuthProperties oauthProperties;
    private final CredentialDomainService credentialService;
    private final IAccountRepository accountRepository;

    @Qualifier("redissonClient")
    private final RedissonClient redissonClient;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 刷新指定账号的 OAuth access_token。
     * 使用分布式锁防止并发刷新同一账号。
     *
     * @param accountId 账号 ID
     * @return 新的 access_token，刷新失败返回 null
     */
    public String refreshAccessToken(Long accountId) {
        RLock lock = redissonClient.getLock(RedisKeys.oauthTokenRefreshLockKey(accountId));
        try {
            if (!lock.tryLock(10, 30, TimeUnit.SECONDS)) {
                log.warn("OAuth token refresh lock busy: account_id={}", accountId);
                return null;
            }
            return doRefresh(accountId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("OAuth token refresh interrupted: account_id={}", accountId);
            return null;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 执行实际的 token 刷新流程（由 {@link #refreshAccessToken} 在持锁后调用）。
     * <ol>
     *   <li>校验账号存在且为 OAUTH 类型</li>
     *   <li>从 credentials 中解密 refresh_token</li>
     *   <li>调用上游 token endpoint 的 refresh_token grant</li>
     *   <li>加密新 tokens 并更新 account 记录</li>
     *   <li>更新 Redis Sorted Set 中的过期时间</li>
     * </ol>
     *
     * @param accountId 账号 ID
     * @return 新的 access_token 明文，失败返回 null
     */
    private String doRefresh(Long accountId) {
        AccountEntity account = accountRepository.findById(accountId).orElse(null);
        if (account == null || account.getDeletedAt() != null) {
            log.warn("Account not found for token refresh: account_id={}", accountId);
            removeProactiveRefresh(accountId);
            return null;
        }

        if (account.getType() != AccountType.OAUTH) {
            log.warn("Account is not OAuth type: account_id={}, type={}", accountId, account.getType());
            return null;
        }

        try {
            JsonNode creds = JSON.readTree(account.getCredentials());
            String refreshToken = extractRefreshToken(creds);
            if (refreshToken == null) {
                log.warn("No refresh_token in account credentials: account_id={}", accountId);
                removeProactiveRefresh(accountId);
                return null;
            }

            String providerKey = creds.has("oauth_provider")
                    ? creds.get("oauth_provider").asText()
                    : account.getPlatform().getKey();

            OAuthProperties.ProviderConfig provider = oauthProperties.getProviders().get(providerKey);
            if (provider == null) {
                log.error("No OAuth provider config for: {}", providerKey);
                return null;
            }

            log.info("Refreshing OAuth token: account_id={}, provider={}", accountId, providerKey);

            String refreshScopes = provider.getRefreshScopes() != null
                    ? provider.getRefreshScopes()
                    : provider.getScopes();

            boolean useJson = "json".equalsIgnoreCase(provider.getTokenExchangeFormat());
            String body;
            String contentType;

            if (useJson) {
                ObjectNode refreshReq = JSON.createObjectNode();
                refreshReq.put("grant_type", "refresh_token");
                refreshReq.put("refresh_token", refreshToken);
                refreshReq.put("client_id", provider.getClientId());
                body = refreshReq.toString();
                contentType = "application/json";
            } else {
                body = "grant_type=refresh_token"
                        + "&refresh_token=" + java.net.URLEncoder.encode(refreshToken, java.nio.charset.StandardCharsets.UTF_8)
                        + "&client_id=" + java.net.URLEncoder.encode(provider.getClientId(), java.nio.charset.StandardCharsets.UTF_8)
                        + "&scope=" + java.net.URLEncoder.encode(refreshScopes, java.nio.charset.StandardCharsets.UTF_8);
                contentType = "application/x-www-form-urlencoded";
            }

            var reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(provider.getTokenUrl()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", contentType);
            if (provider.getUserAgent() != null && !provider.getUserAgent().isEmpty()) {
                reqBuilder.header("User-Agent", provider.getUserAgent());
            }
            HttpRequest req = reqBuilder.POST(HttpRequest.BodyPublishers.ofString(body)).build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("OAuth token refresh failed: account_id={}, status={}, body={}",
                        accountId, resp.statusCode(), resp.body());
                if (resp.statusCode() == 400 || resp.statusCode() == 401) {
                    markAccountError(account, "Refresh token rejected: HTTP " + resp.statusCode());
                }
                return null;
            }

            JsonNode tokenResp = JSON.readTree(resp.body());
            String newAccessToken = tokenResp.has("access_token") ? tokenResp.get("access_token").asText() : null;
            String newRefreshToken = tokenResp.has("refresh_token") ? tokenResp.get("refresh_token").asText() : refreshToken;
            long expiresIn = tokenResp.has("expires_in") ? tokenResp.get("expires_in").asLong() : 3600;

            if (newAccessToken == null) {
                log.error("No access_token in refresh response: account_id={}", accountId);
                return null;
            }

            updateAccountCredentials(account, newAccessToken, newRefreshToken,
                    Instant.now().plusSeconds(expiresIn), providerKey);
            scheduleProactiveRefresh(accountId, Instant.now().plusSeconds(expiresIn));

            log.info("OAuth token refreshed successfully: account_id={}, expires_in={}s", accountId, expiresIn);
            return newAccessToken;

        } catch (Exception e) {
            log.error("OAuth token refresh error: account_id={}", accountId, e);
            return null;
        }
    }

    /**
     * 加密 tokens 并更新账号的 credentials JSON 字段。
     */
    @Transactional
    private void updateAccountCredentials(AccountEntity account, String accessToken,
                                           String refreshToken, Instant expiresAt, String providerKey) {
        try {
            String encryptedAccess = credentialService.encrypt(accessToken);
            String encryptedRefresh = credentialService.encrypt(refreshToken);

            ObjectNode newCreds = JSON.createObjectNode();
            newCreds.put("access_token", encryptedAccess);
            newCreds.put("refresh_token", encryptedRefresh);
            newCreds.put("token_expires_at", expiresAt.toString());
            newCreds.put("oauth_provider", providerKey);

            account.setCredentials(newCreds.toString());
            accountRepository.save(account);
        } catch (Exception e) {
            log.error("Failed to encrypt OAuth tokens: account_id={}", account.getId(), e);
            throw new RuntimeException("Failed to encrypt OAuth tokens", e);
        }
    }

    /**
     * 从 credentials JSON 中解密并返回 refresh_token。
     *
     * @return refresh_token 明文，不存在或为空时返回 null
     */
    private String extractRefreshToken(JsonNode creds) {
        if (!creds.has("refresh_token")) return null;
        String encrypted = creds.get("refresh_token").asText();
        if (encrypted == null || encrypted.isEmpty()) return null;
        try {
            return credentialService.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("Failed to decrypt refresh_token, trying raw value");
            return encrypted;
        }
    }

    /**
     * 将账号标记为 ERROR 状态并从主动刷新调度中移除。
     */
    private void markAccountError(AccountEntity account, String reason) {
        account.setStatus(com.landgate.types.enums.Status.ERROR);
        account.setErrorMessage("OAuth refresh: " + reason);
        accountRepository.save(account);
        removeProactiveRefresh(account.getId());
        log.warn("Account marked ERROR due to OAuth refresh failure: account_id={}, reason={}",
                account.getId(), reason);
    }

    // ==================== Redis Sorted Set 主动刷新调度 ====================

    /**
     * 将账号加入主动刷新调度——存入 Redis Sorted Set，score 为 token 过期时间。
     */
    public void scheduleProactiveRefresh(Long accountId, Instant expiresAt) {
        RScoredSortedSet<String> set = redissonClient.getScoredSortedSet(
                RedisKeys.OAUTH_TOKEN_EXPIRY_KEY);
        double score = expiresAt.getEpochSecond();
        set.add(score, accountId.toString());
        log.debug("Scheduled proactive refresh: account_id={}, expires_at={}", accountId, expiresAt);
    }

    /**
     * 从主动刷新调度中移除账号。
     */
    public void removeProactiveRefresh(Long accountId) {
        RScoredSortedSet<String> set = redissonClient.getScoredSortedSet(
                RedisKeys.OAUTH_TOKEN_EXPIRY_KEY);
        set.remove(accountId.toString());
        log.debug("Removed from proactive refresh: account_id={}", accountId);
    }
}
