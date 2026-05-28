package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.enums.AccountType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 协议请求转换器 —— 构建转发至 OpenAI API 的上游请求。
 * <p>
 * 负责：设置认证头 → 配置代理 → 上游路径选择（基于账号类型和能力探测）。
 * <p>
 * 上游 URL 选择策略：
 * <ul>
 *   <li><strong>OAuth 账号</strong>：固定 {@code https://chatgpt.com/backend-api/codex/responses}
 *       （ChatGPT 内部 Codex 端点，不经过 /v1/responses vs /v1/chat/completions 二选一）</li>
 *   <li><strong>API Key + 支持 Responses</strong>：{@code /v1/responses}</li>
 *   <li><strong>API Key + 不支持 Responses</strong>：{@code /v1/chat/completions}</li>
 * </ul>
 */
@Slf4j
@Component
public class OpenAiTransformer implements IRequestTransformer {

    private static final String OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";
    private static final String OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";
    /** ChatGPT 内部 Codex 端点（OAuth 账号专用） */
    private static final String CODEX_RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final UpstreamCapabilityService upstreamCapabilityService;

    public OpenAiTransformer(UpstreamCapabilityService upstreamCapabilityService) {
        this.upstreamCapabilityService = upstreamCapabilityService;
    }

    @Override
    public HttpRequest buildUpstreamRequest(String body, AccountEntity account, String accessToken) {
        // 根据当前请求上下文中的 requestFormat 选择上游路径
        GatewayRequestContext ctx = GatewayRequestContext.get();

        String targetUrl;
        boolean isOAuth = account.getType() == AccountType.OAUTH;

        if (isOAuth) {
            // OAuth 账号：固定走 Codex 端点（ChatGPT 内部 Responses API）
            targetUrl = CODEX_RESPONSES_URL;
        } else {
            boolean useResponses = ctx != null && "responses".equals(ctx.getRequestFormat())
                    && upstreamCapabilityService.shouldUseResponsesAPI(account);
            String pathSuffix = useResponses ? "/v1/responses" : "/v1/chat/completions";
            targetUrl = useResponses ? OPENAI_RESPONSES_URL : OPENAI_CHAT_URL;

            if (account.getExtra() != null && !account.getExtra().equals("{}")) {
                try {
                    var extra = JSON.readTree(account.getExtra());
                    if (extra.has("base_url") && !extra.get("base_url").asText().isEmpty()) {
                        targetUrl = extra.get("base_url").asText() + pathSuffix;
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse base_url for OpenAI account: account_id={}", account.getId());
                }
            }
        }

        log.debug("OpenAI upstream URL: url={}, account_id={}, isOAuth={}", targetUrl, account.getId(), isOAuth);

        var headers = new ArrayList<String>();
        headers.addAll(List.of("Authorization", "Bearer " + accessToken));
        headers.addAll(List.of("Content-Type", "application/json"));

        return HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(120))
                .headers(headers.toArray(new String[0]))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    @Override
    public String extractUserId(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has("user")) {
                JsonNode userNode = root.get("user");
                if (userNode.isTextual()) return userNode.asText();
            }
        } catch (Exception e) {
            log.debug("Failed to extract user from OpenAI request body");
        }
        return null;
    }

    @Override
    public String extractModel(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has("model")) return root.get("model").asText();
        } catch (Exception e) {
            log.debug("Failed to extract model from OpenAI request body");
        }
        return "unknown";
    }

    @Override
    public boolean isStreamRequest(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has("stream")) return root.get("stream").asBoolean();
        } catch (Exception e) {
            // ignore
        }
        return false;
    }
}
