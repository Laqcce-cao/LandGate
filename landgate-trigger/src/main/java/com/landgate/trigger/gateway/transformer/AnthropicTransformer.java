package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.oauth.BillingHeaderService;
import com.landgate.trigger.gateway.oauth.FingerprintService;
import com.landgate.trigger.gateway.oauth.OAuthMimicryService;
import com.landgate.trigger.gateway.oauth.UserIdRewriter;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FingerprintService fingerprintService;
    private final UserIdRewriter userIdRewriter;
    private final BillingHeaderService billingHeaderService;
    private final OAuthMimicryService oAuthMimicryService;

    public AnthropicTransformer(
            FingerprintService fingerprintService,
            UserIdRewriter userIdRewriter,
            BillingHeaderService billingHeaderService,
            OAuthMimicryService oAuthMimicryService) {
        this.fingerprintService = fingerprintService;
        this.userIdRewriter = userIdRewriter;
        this.billingHeaderService = billingHeaderService;
        this.oAuthMimicryService = oAuthMimicryService;
    }

    public HttpRequest buildUpstreamRequest(UpstreamRequestContext context) {
        String body = context.body();
        AccountEntity account = context.account();
        String accessToken = context.accessToken();
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

            // 2. 重写 metadata.user_id（替换 device_id → ClientID, session_id → 新 hash）
            // TODO: 当 enable_metadata_passthrough 开关实现后，添加条件判断
            String accountUUID = extractAccountUUID(account);
            if (accountUUID != null && !accountUUID.isEmpty() && fp != null) {
                body = userIdRewriter.rewriteUserIDWithMasking(
                        body, account, accountUUID, fp.getClientId(), fp.getUserAgent());
            }

            // 3. 同步 billing header 版本（仅在 fingerprint 存在时执行）
            if (fp != null) {
                body = billingHeaderService.syncBillingHeaderVersion(body, fp.getUserAgent());
            }

            // 4. 签名 CCH（必须是最后一步 body 修改！xxHash64 对整个 body 计算）
            // TODO: 当 enable_cch_signing 开关实现后，添加条件判断
            // body = billingHeaderService.signBillingHeaderCCH(body);

            // 5. 同步 X-Claude-Code-Session-Id（真实 CC 客户端）
            // RewriteUserIDWithMasking 重写了 body 中 metadata.user_id 的 session_id，
            // 必须同步请求头中的 X-Claude-Code-Session-Id 为新 session_id
            if (!context.shouldMimicClaudeCode()) {
                String sessionHeader = requestHeaders != null
                        ? requestHeaders.get("X-Claude-Code-Session-Id") : null;
                if (sessionHeader != null && !sessionHeader.isEmpty()) {
                    String newSessionId = userIdRewriter.extractCurrentSessionId(body);
                    if (newSessionId != null) {
                        // session ID 同步通过 Header 数组方式注入（见下方 headers 构建）
                    }
                }
            }
        }

        body = AnthropicCacheControlPolicy.enforceLimit(body);

        String modelName = extractModel(body);
        String targetUrl = resolveTargetUrl(account, context);

        var headers = buildHeaders(account, accessToken, context);
        log.debug("Building upstream request: url={}, model={}, account_id={}", targetUrl, modelName, account.getId());

        var requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .headers(headers)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        // 6. 应用伪装头 + 指纹头（Phase B 的 header 级操作）
        if (isAnthropicOAuth(account)) {
            if (context.shouldMimicClaudeCode()) {
                oAuthMimicryService.applyClaudeCodeMimicHeaders(requestBuilder,
                        context.stream(), modelName);
            } else {
                // 真实 CC 客户端：仅应用基础 OAuth 头 + 指纹头
                oAuthMimicryService.applyOAuthHeaderDefaults(requestBuilder);
            }
        }

        return requestBuilder.build();
    }

    /** 解析上游目标地址，正常网关路径优先使用策略路由结果。 */
    private String resolveTargetUrl(AccountEntity account, UpstreamRequestContext context) {
        if (context.upstreamRoute() != null && context.upstreamRoute().targetUrl() != null) {
            return context.upstreamRoute().targetUrl();
        }
        String targetUrl = ANTHROPIC_API_URL;
        if (account.getExtra() != null && !account.getExtra().equals("{}")) {
            try {
                JsonNode extra = JSON.readTree(account.getExtra());
                if (extra.has("base_url") && !extra.get("base_url").asText().isEmpty()) {
                    targetUrl = extra.get("base_url").asText() + "/v1/messages";
                }
            } catch (Exception e) {
                log.warn("Failed to parse account extra for base_url: account_id={}", account.getId());
            }
        }
        return targetUrl;
    }

    private String[] buildHeaders(AccountEntity account, String accessToken, UpstreamRequestContext context) {
        var headers = new ArrayList<String>();
        if (account.getType() == AccountType.OAUTH || account.getType() == AccountType.SETUP_TOKEN) {
            headers.addAll(List.of("Authorization", "Bearer " + accessToken));
        } else {
            headers.addAll(List.of("x-api-key", accessToken));
        }
        headers.addAll(List.of("anthropic-version", ANTHROPIC_VERSION));

        // OAuth 账号的 anthropic-beta：伪装模式下由 applyClaudeCodeMimicHeaders 设置，
        // 非伪装模式（真实 CC 客户端）使用基础 beta
        if (isAnthropicOAuth(account)) {
            if (context.shouldMimicClaudeCode()) {
                // 伪装模式：beta 头在 applyClaudeCodeMimicHeaders 中设置
                headers.addAll(List.of("anthropic-beta", "oauth-2025-04-20"));
            } else {
                // 真实 CC 客户端：仅基础 oauth beta
                headers.addAll(List.of("anthropic-beta", "oauth-2025-04-20"));
            }
        }

        return headers.toArray(new String[0]);
    }

    @Override
    public String extractUserId(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has("metadata") && root.get("metadata").has("user_id")) {
                return root.get("metadata").get("user_id").asText();
            }
        } catch (Exception e) {
            log.debug("Failed to extract user_id from Anthropic request body");
        }
        return null;
    }

    public String extractModel(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has("model")) return root.get("model").asText();
        } catch (Exception e) {
            log.debug("Failed to extract model from request body");
        }
        return "unknown";
    }

    public boolean isStreamRequest(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has("stream")) return root.get("stream").asBoolean();
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
                if (obj.has("metadata") && obj.get("metadata").isObject()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) obj.get("metadata")).put("user_id", userId);
                } else {
                    var metadata = JSON.createObjectNode();
                    metadata.put("user_id", userId);
                    obj.set("metadata", metadata);
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
        return account.getPlatform() == Platform.ANTHROPIC
                && (account.getType() == AccountType.OAUTH
                    || account.getType() == AccountType.SETUP_TOKEN);
    }

    /** 提取账号的 account_uuid（从 extra JSON 中读取） */
    private static String extractAccountUUID(AccountEntity account) {
        try {
            if (account.getExtra() != null && !account.getExtra().equals("{}")) {
                JsonNode extra = JSON.readTree(account.getExtra());
                if (extra.has("account_uuid")) {
                    return extra.get("account_uuid").asText().trim();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }
}
