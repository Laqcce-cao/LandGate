package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.oauth.BillingHeaderService;
import com.landgate.trigger.gateway.oauth.FingerprintService;
import com.landgate.trigger.gateway.oauth.OAuthMimicryService;
import com.landgate.trigger.gateway.oauth.UserIdRewriter;
import com.landgate.trigger.gateway.forwarding.AnthropicForwardingRuntimePolicyProvider;
import com.landgate.types.gateway.AnthropicAccountAuthPolicy;
import com.landgate.types.gateway.AnthropicApiProfile;
import com.landgate.types.gateway.AnthropicCacheControlPolicy;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import com.landgate.types.gateway.AnthropicForwardingRuntimePolicy;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.GatewayHeaderPolicy;
import com.landgate.types.gateway.GatewayRequestBodyPolicy;
import com.landgate.trigger.gateway.route.UpstreamEndpointDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anthropic 协议请求转换器 —— 构建转发至 Anthropic API 的上游请求。
 * <p>
 * 负责：设置 API 版本头 → 注入认证 Token → 配置代理 → 检查模型过滤。
 * <p>
 * Phase B（OAuth 伪装）：指纹管理 → metadata.user_id 重写 → billing header 版本同步 →
 * CCH 签名 → 伪装头注入。
 */
@Slf4j
@Component
public class AnthropicTransformer implements IRequestTransformer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FingerprintService fingerprintService;
    private final UserIdRewriter userIdRewriter;
    private final BillingHeaderService billingHeaderService;
    private final OAuthMimicryService oAuthMimicryService;
    private final AnthropicForwardingRuntimePolicyProvider forwardingRuntimePolicyProvider;

    @Autowired
    public AnthropicTransformer(
            FingerprintService fingerprintService,
            UserIdRewriter userIdRewriter,
            BillingHeaderService billingHeaderService,
            OAuthMimicryService oAuthMimicryService,
            AnthropicForwardingRuntimePolicyProvider forwardingRuntimePolicyProvider) {
        this.fingerprintService = fingerprintService;
        this.userIdRewriter = userIdRewriter;
        this.billingHeaderService = billingHeaderService;
        this.oAuthMimicryService = oAuthMimicryService;
        this.forwardingRuntimePolicyProvider = forwardingRuntimePolicyProvider;
    }

    public AnthropicTransformer(
            FingerprintService fingerprintService,
            UserIdRewriter userIdRewriter,
            BillingHeaderService billingHeaderService,
            OAuthMimicryService oAuthMimicryService) {
        this(fingerprintService, userIdRewriter, billingHeaderService, oAuthMimicryService, null);
    }

    public HttpRequest buildUpstreamRequest(UpstreamRequestContext context) {
        String body = context.body();
        AccountEntity account = context.account();
        String accessToken = context.accessToken();
        String syncedClaudeCodeSessionId = "";
        FingerprintService.ClientFingerprint oauthFingerprint = null;
        AnthropicForwardingRuntimePolicy forwardingPolicy = currentForwardingPolicy();
        // 如果上下文中存在 metadata.user_id（从原始 Anthropic 客户端请求提取的），
        // 重新注入到上游请求中（因为 Anthropic→Responses IR 转换会丢弃 metadata 字段）
        if (context.metadataUserId() != null) {
            body = injectMetadataUserId(body, context.metadataUserId());
        }

        // ========================
        // Phase B: OAuth 伪装（仅对 Anthropic 平台 OAuth/SetupToken 账号生效）
        // ========================
        if (isAnthropicOAuth(account)) {
            java.util.Map<String, String> requestHeaders = context.requestHeaders();

            // 1. 指纹管理
            FingerprintService.ClientFingerprint fp =
                    fingerprintService.getOrCreateFingerprint(account.getId(), requestHeaders);
            oauthFingerprint = forwardingPolicy.fingerprintUnification() ? fp : null;

            // 2. 重写 metadata.user_id（替换 device_id → ClientID, session_id → 新 hash）
            String accountUUID = extractAccountUUID(account);
            if (!forwardingPolicy.metadataPassthrough()
                    && accountUUID != null && !accountUUID.isEmpty() && fp != null) {
                body = userIdRewriter.rewriteUserIDWithMasking(
                        body, account, accountUUID, fp.getClientId(), fp.getUserAgent());
            }

            // 3. 同步 billing header 版本（仅在 fingerprint 存在时执行）
            if (forwardingPolicy.fingerprintUnification() && fp != null) {
                body = billingHeaderService.syncBillingHeaderVersion(body, fp.getUserAgent());
            }

            // 4. 签名 CCH（必须是最后一步 body 修改！xxHash64 对整个 body 计算）
            if (forwardingPolicy.cchSigning()) {
                body = billingHeaderService.signBillingHeaderCCH(body);
            }

            // 5. 同步 X-Claude-Code-Session-Id（真实 CC 客户端）
            // RewriteUserIDWithMasking 重写了 body 中 metadata.user_id 的 session_id，
            // 必须同步请求头中的 X-Claude-Code-Session-Id 为新 session_id
            if (!context.shouldMimicClaudeCode()) {
                if (GatewayHeaderPolicy.hasValue(requestHeaders, AnthropicApiProfile.HEADER_X_CLAUDE_CODE_SESSION_ID)) {
                    String newSessionId = userIdRewriter.extractCurrentSessionId(body);
                    if (newSessionId != null) {
                        syncedClaudeCodeSessionId = newSessionId;
                    }
                }
            }

            if (oAuthMimicryService != null) {
                body = oAuthMimicryService.applyAnthropicCacheControlTTL1hIfEnabled(body);
            }
        }

        body = AnthropicEmptyTextBlockNormalizer.normalize(body);
        body = AnthropicModelMappingBodyNormalizer.apply(account, body);
        body = AnthropicCacheControlPolicy.enforceLimit(body);

        String modelName = extractModel(body);
        String targetUrl = resolveTargetUrl(account, context);

        Map<String, String> upstreamHeaders = normalizeApiKeyHeaders(account, context.requestHeaders(), forwardingPolicy);
        var headers = buildHeaders(account, accessToken, upstreamHeaders);
        log.debug("Building upstream request: url={}, model={}, account_id={}", targetUrl, modelName, account.getId());

        var requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(120))
                .headers(headers)
                .setHeader(AnthropicApiProfile.HEADER_CONTENT_TYPE, AnthropicApiProfile.MEDIA_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (!syncedClaudeCodeSessionId.isBlank()) {
            requestBuilder.setHeader(AnthropicApiProfile.HEADER_X_CLAUDE_CODE_SESSION_ID, syncedClaudeCodeSessionId);
        }

        // 6. 应用伪装头 + 指纹头（Phase B 的 header 级操作）
        if (isAnthropicOAuth(account)) {
            if (context.shouldMimicClaudeCode()) {
                oAuthMimicryService.applyClaudeCodeMimicHeaders(requestBuilder,
                        context.stream(), modelName, context.requestHeaders(), forwardingPolicy.betaDropTokens());
            } else {
                // 真实 CC 客户端：仅应用基础 OAuth 头 + 指纹头
                oAuthMimicryService.applyOAuthHeaderDefaults(
                        requestBuilder, modelName, context.requestHeaders(), forwardingPolicy.betaDropTokens());
                fingerprintService.applyFingerprint(requestBuilder, oauthFingerprint);
            }
        }

        return requestBuilder.build();
    }

    /** 解析上游目标地址，正常网关路径优先使用策略路由结果。 */
    private String resolveTargetUrl(AccountEntity account, UpstreamRequestContext context) {
        if (context.upstreamRoute() != null && context.upstreamRoute().targetUrl() != null) {
            return context.upstreamRoute().targetUrl();
        }
        return UpstreamEndpointDefaults.anthropicMessagesUrl(account);
    }

    private String[] buildHeaders(AccountEntity account, String accessToken, java.util.Map<String, String> requestHeaders) {
        return AnthropicAuthProfile.from(account).buildHeaders(accessToken, requestHeaders);
    }

    private static Map<String, String> normalizeApiKeyHeaders(AccountEntity account,
                                                              Map<String, String> requestHeaders,
                                                              AnthropicForwardingRuntimePolicy forwardingPolicy) {
        if (isAnthropicOAuth(account) || requestHeaders == null || requestHeaders.isEmpty()) {
            return requestHeaders == null ? Map.of() : requestHeaders;
        }
        AnthropicForwardingRuntimePolicy effective =
                forwardingPolicy == null ? AnthropicForwardingRuntimePolicy.defaults() : forwardingPolicy;
        if (effective.betaDropTokens().isEmpty()
                || !GatewayHeaderPolicy.hasValue(requestHeaders, AnthropicApiProfile.HEADER_ANTHROPIC_BETA)) {
            return requestHeaders;
        }

        Map<String, String> normalized = new LinkedHashMap<>(requestHeaders);
        String stripped = AnthropicClaudeCodeProfile.stripBetaTokens(
                GatewayHeaderPolicy.value(requestHeaders, AnthropicApiProfile.HEADER_ANTHROPIC_BETA),
                effective.betaDropTokens());
        normalized.entrySet().removeIf(entry ->
                entry.getKey() != null
                        && entry.getKey().equalsIgnoreCase(AnthropicApiProfile.HEADER_ANTHROPIC_BETA));
        if (!stripped.isBlank()) {
            normalized.put(AnthropicApiProfile.HEADER_ANTHROPIC_BETA, stripped);
        }
        return normalized;
    }

    private AnthropicForwardingRuntimePolicy currentForwardingPolicy() {
        return forwardingRuntimePolicyProvider == null
                ? AnthropicForwardingRuntimePolicy.defaults()
                : forwardingRuntimePolicyProvider.current();
    }

    @Override
    public String extractUserId(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has(AnthropicMessagesBodyPolicy.FIELD_METADATA)
                    && root.get(AnthropicMessagesBodyPolicy.FIELD_METADATA)
                    .has(AnthropicMessagesBodyPolicy.FIELD_USER_ID)) {
                return root.get(AnthropicMessagesBodyPolicy.FIELD_METADATA)
                        .get(AnthropicMessagesBodyPolicy.FIELD_USER_ID)
                        .asText();
            }
        } catch (Exception e) {
            log.debug("Failed to extract user_id from Anthropic request body");
        }
        return null;
    }

    public String extractModel(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has(AnthropicMessagesBodyPolicy.FIELD_MODEL)) {
                return root.get(AnthropicMessagesBodyPolicy.FIELD_MODEL).asText();
            }
        } catch (Exception e) {
            log.debug("Failed to extract model from request body");
        }
        return GatewayRequestBodyPolicy.DEFAULT_MODEL;
    }

    public boolean isStreamRequest(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has(AnthropicMessagesBodyPolicy.FIELD_STREAM)) {
                return root.get(AnthropicMessagesBodyPolicy.FIELD_STREAM).asBoolean();
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    /**
     * 将 metadata.user_id 注入到 Anthropic 请求 body 中。
     * <p>
     * 如果 body 中已存在 metadata 对象则更新其 user_id，否则创建新的 metadata 对象。
     */
    private String injectMetadataUserId(String body, String userId) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.isObject()) {
                var obj = (com.fasterxml.jackson.databind.node.ObjectNode) root;
                if (obj.has(AnthropicMessagesBodyPolicy.FIELD_METADATA)
                        && obj.get(AnthropicMessagesBodyPolicy.FIELD_METADATA).isObject()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) obj.get(AnthropicMessagesBodyPolicy.FIELD_METADATA))
                            .put(AnthropicMessagesBodyPolicy.FIELD_USER_ID, userId);
                } else {
                    var metadata = JSON.createObjectNode();
                    metadata.put(AnthropicMessagesBodyPolicy.FIELD_USER_ID, userId);
                    obj.set(AnthropicMessagesBodyPolicy.FIELD_METADATA, metadata);
                }
                return JSON.writeValueAsString(obj);
            }
        } catch (Exception e) {
            log.warn("Failed to inject metadata.user_id into upstream request: {}", e.getMessage());
        }
        return body;
    }

    /** 判断是否为 Anthropic 平台的 OAuth/SetupToken 账号 */
    private static boolean isAnthropicOAuth(AccountEntity account) {
        return account != null
                && AnthropicAccountAuthPolicy.isAnthropicOAuthOrSetupToken(
                account.getPlatform(), account.getType());
    }

    /** 提取账号的 account_uuid（从 extra JSON 中读取） */
    private static String extractAccountUUID(AccountEntity account) {
        try {
            if (account.getExtra() != null && !account.getExtra().equals("{}")) {
                JsonNode extra = JSON.readTree(account.getExtra());
                if (extra.has(AnthropicClaudeCodeProfile.ACCOUNT_EXTRA_ACCOUNT_UUID)) {
                    return extra.get(AnthropicClaudeCodeProfile.ACCOUNT_EXTRA_ACCOUNT_UUID).asText().trim();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }
}
