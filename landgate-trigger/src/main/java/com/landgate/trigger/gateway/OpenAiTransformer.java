package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.AccountType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * OpenAI 协议请求转换器 —— 根据已解析的 {@link UpstreamRoute} 构建 OpenAI 上游 HTTP 请求。
 * <p>
 * 负责：设置认证头、Content-Type、超时和请求体；endpoint 选择由 route strategy 完成。
 * 无 {@link GatewayRequestContext} 的直接调用仅保留兼容 fallback，不作为正常网关路由路径。
 */
@Slf4j
@Component
public class OpenAiTransformer implements IRequestTransformer {

    private static final String OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";
    private static final String OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";
    /** ChatGPT 内部 Codex 端点（OAuth 账号专用） */
    private static final String CODEX_RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** ChatGPT 内部 Codex 端点不支持的请求字段（参考 sub2api OpenAI OAuth transform） */
    private static final Set<String> CODEX_UNSUPPORTED_FIELDS = Set.of(
            "max_output_tokens", "max_completion_tokens", "temperature", "top_p",
            "frequency_penalty", "presence_penalty", "user", "metadata",
            "prompt_cache_retention", "safety_identifier", "stream_options");

    public OpenAiTransformer() {
    }

    @Override
    public HttpRequest buildUpstreamRequest(String body, AccountEntity account, String accessToken) {
        // 根据当前请求上下文中的 requestFormat 选择上游路径
        GatewayRequestContext ctx = GatewayRequestContext.get();

        String targetUrl;
        boolean isOAuth = account.getType() == AccountType.OAUTH;
        UpstreamRoute route = ctx != null ? ctx.getUpstreamRoute() : null;

        if (route != null) {
            targetUrl = route.targetUrl();
            if (route.normalizeCodexOAuthBody()) {
                body = normalizeCodexOAuthRequestBody(body, account);
            }
        } else if (isOAuth) {
            // 兼容无 GatewayRequestContext 的单元调用；正常网关路径由 UpstreamRoute 决定端点。
            targetUrl = CODEX_RESPONSES_URL;
            body = normalizeCodexOAuthRequestBody(body, account);
        } else {
            targetUrl = OPENAI_CHAT_URL;
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

    /**
     * 规范化 OpenAI OAuth Codex 请求体。
     * <p>
     * ChatGPT 内部 Codex 端点不是公开 Responses API：它要求顶层 instructions，
     * 且不支持 max_output_tokens 等公开 Responses 字段。此逻辑只在 OAuth Codex
     * 路由层执行，避免影响普通 OpenAI API Key 的 Responses 请求。
     */
    private String normalizeCodexOAuthRequestBody(String body, AccountEntity account) {
        try {
            ObjectNode root = (ObjectNode) JSON.readTree(body);

            for (String field : CODEX_UNSUPPORTED_FIELDS) {
                root.remove(field);
            }
            root.put("store", false);
            // ChatGPT Codex 内部 Responses 端点只接受流式请求；客户端非流式由网关聚合后返回。
            root.put("stream", true);

            extractSystemMessagesToInstructions(root);
            if (isBlankText(root.get("instructions"))) {
                root.put("instructions", "You are a helpful coding assistant.");
            }

            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Failed to normalize Codex OAuth request body: account_id={}", account.getId(), e);
            return body;
        }
    }

    /** 将 input 中的 system/developer role 消息移入顶层 instructions，并从 input 中移除。 */
    private static void extractSystemMessagesToInstructions(ObjectNode root) {
        JsonNode inputNode = root.get("input");
        if (inputNode == null || !inputNode.isArray()) return;

        List<String> systemTexts = new ArrayList<>();
        ArrayNode filtered = JSON.createArrayNode();
        for (JsonNode item : inputNode) {
            String role = item.has("role") ? item.get("role").asText() : "";
            if ("system".equals(role) || "developer".equals(role)) {
                String text = extractTextFromContent(item.get("content"));
                if (!text.isBlank()) systemTexts.add(text);
            } else {
                filtered.add(item);
            }
        }
        if (systemTexts.isEmpty()) return;

        String extracted = String.join("\n\n", systemTexts);
        JsonNode existing = root.get("instructions");
        if (!isBlankText(existing)) {
            root.put("instructions", extracted + "\n\n" + existing.asText());
        } else {
            root.put("instructions", extracted);
        }
        root.set("input", filtered);
    }

    /** 提取 Responses content 中的文本，兼容字符串和 content parts 数组。 */
    private static String extractTextFromContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) return "";
        if (contentNode.isTextual()) return contentNode.asText();
        if (contentNode.isArray()) {
            List<String> texts = new ArrayList<>();
            for (JsonNode part : contentNode) {
                if (part.has("text")) {
                    String text = part.get("text").asText();
                    if (!text.isBlank()) texts.add(text);
                }
            }
            return String.join("\n", texts);
        }
        return contentNode.asText();
    }

    /** 判断 instructions 是否缺失或为空白。 */
    private static boolean isBlankText(JsonNode node) {
        return node == null || node.isNull() || !node.isTextual() || node.asText().isBlank();
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
