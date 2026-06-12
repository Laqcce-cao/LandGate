package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.AccountType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
            "service_tier", "prompt_cache_key", "prompt_cache_retention",
            "safety_identifier", "top_logprobs", "stream_options",
            "include", "previous_response_id", "truncation", "prompt",
            "background", "conversation", "context_management",
            "parallel_tool_calls", "max_tool_calls");

    @Value("${landgate.gateway.codex.preserve-prompt-cache-key:false}")
    private boolean preservePromptCacheKey;

    public OpenAiTransformer() {
    }

    @Override
    public HttpRequest buildUpstreamRequest(UpstreamRequestContext context) {
        String body = context.body();
        AccountEntity account = context.account();
        String accessToken = context.accessToken();
        String targetUrl;
        boolean isOAuth = account.getType() == AccountType.OAUTH;
        UpstreamRoute route = context.upstreamRoute();

        if (route != null) {
            targetUrl = route.targetUrl();
            if (route.normalizeCodexOAuthBody()) {
                body = normalizeCodexOAuthRequestBody(body, account, route, context.requestId());
            } else if (route.endpointKind() == EndpointKind.OPENAI_CHAT_COMPLETIONS) {
                body = ensureChatCompletionsStreamUsage(body);
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
     * OpenAI Chat Completions 流式响应默认不返回 usage，必须显式要求上游返回最终用量 chunk。
     */
    private String ensureChatCompletionsStreamUsage(String body) {
        try {
            JsonNode parsed = JSON.readTree(body);
            if (!(parsed instanceof ObjectNode root)) return body;
            if (!root.path("stream").asBoolean(false)) return body;

            JsonNode streamOptionsNode = root.get("stream_options");
            ObjectNode streamOptions = streamOptionsNode instanceof ObjectNode objectNode
                    ? objectNode
                    : JSON.createObjectNode();
            streamOptions.put("include_usage", true);
            root.set("stream_options", streamOptions);
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Failed to ensure Chat Completions stream usage option", e);
            return body;
        }
    }

    /**
     * 规范化 OpenAI OAuth Codex 请求体。
     * <p>
     * ChatGPT 内部 Codex 端点不是公开 Responses API：它要求顶层 instructions，
     * 且不支持 max_output_tokens 等公开 Responses 字段。此逻辑只在 OAuth Codex
     * 路由层执行，避免影响普通 OpenAI API Key 的 Responses 请求。
     */
    private String normalizeCodexOAuthRequestBody(String body, AccountEntity account) {
        return normalizeCodexOAuthRequestBody(body, account, null, null);
    }

    private String normalizeCodexOAuthRequestBody(String body, AccountEntity account,
                                                  UpstreamRoute route, String requestId) {
        try {
            ObjectNode root = (ObjectNode) JSON.readTree(body);
            CodexRequestShape beforeShape = CodexRequestShape.from(root, body);

            for (String field : CODEX_UNSUPPORTED_FIELDS) {
                if (shouldRemoveCodexField(field)) {
                    root.remove(field);
                }
            }
            if (isCodexCompactEndpoint(route)) {
                root.remove("store");
                root.remove("stream");
            } else {
                root.put("store", false);
                // ChatGPT Codex 内部 Responses 端点只接受流式请求；客户端非流式由网关聚合后返回。
                root.put("stream", true);
            }

            extractSystemMessagesToInstructions(root);
//            // 过滤上游不支持的 tool 类型（如 Codex CLI 的 namespace MCP 工具）
//            filterUnsupportedTools(root);
            if (isBlankText(root.get("instructions"))) {
                root.put("instructions", "You are a helpful coding assistant.");
            }

            String normalized = JSON.writeValueAsString(root);
            CodexRequestShape afterShape = CodexRequestShape.from(root, normalized);
            logCodexNormalizationDiagnostics(requestId, account, route, beforeShape, afterShape);
            return normalized;
        } catch (Exception e) {
            log.warn("Failed to normalize Codex OAuth request body: account_id={}", account.getId(), e);
            return body;
        }
    }

    private static boolean isCodexCompactEndpoint(UpstreamRoute route) {
        if (route == null || route.targetUrl() == null || route.targetUrl().isBlank()) {
            return false;
        }
        try {
            return URI.create(route.targetUrl()).getPath().endsWith("/compact");
        } catch (Exception ignored) {
            return route.targetUrl().endsWith("/compact");
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

//    /** 过滤上游不支持的 tool 类型（如 Codex CLI 的 namespace MCP 工具），只保留 web_search 系列。 */
//    private static void filterUnsupportedTools(ObjectNode root) {
//        JsonNode tools = root.get("tools");
//        if (tools == null || !tools.isArray()) return;
//
//        ArrayNode filtered = JSON.createArrayNode();
//        for (JsonNode tool : tools) {
//            String type = tool.has("type") ? tool.get("type").asText() : "";
//            if (type.startsWith("web_search")) {
//                filtered.add(tool);
//            }
//        }
//        root.set("tools", filtered);
//    }

    /** 判断 instructions 是否缺失或为空白。 */
    private static boolean isBlankText(JsonNode node) {
        return node == null || node.isNull() || !node.isTextual() || node.asText().isBlank();
    }

    private boolean shouldRemoveCodexField(String field) {
        return !("prompt_cache_key".equals(field) && preservePromptCacheKey);
    }

    private static void logCodexNormalizationDiagnostics(String requestId,
                                                         AccountEntity account,
                                                         UpstreamRoute route,
                                                         CodexRequestShape before,
                                                         CodexRequestShape after) {
        if (!log.isInfoEnabled()) return;
        GatewayRequestContext ctx = GatewayRequestContext.get();
        String effectiveRequestId = requestId != null && !requestId.isBlank()
                ? requestId
                : (ctx != null ? ctx.getRequestId() : "-");
        String endpoint = route != null && route.endpointKind() != null ? route.endpointKind().name() : "unknown";
        log.info("[{}] Codex OAuth 请求规范化诊断: account_id={}, endpoint={}, preserve_prompt_cache_key={}, body_bytes={}->{}, body_hash={}->{}, "
                        + "prefix64k_hash={}->{}, prefix128k_hash={}->{}, prefix192k_hash={}->{}, prefix256k_hash={}->{}, prefix384k_hash={}->{}, "
                        + "input_items={}->{}, input_bytes={}->{}, max_input_item={}->{}, tail_input_items={}->{}, "
                        + "system_or_developer_items={}->{}, instructions={}->{}, tools={}->{}, "
                        + "prompt_cache_key={}->{}, prompt_cache_retention={}->{}, previous_response_id={}->{}, conversation={}->{}, "
                        + "metadata={}->{}, store={}->{}, stream={}->{}",
                effectiveRequestId,
                account != null ? account.getId() : null,
                endpoint,
                after.hasPromptCacheKey,
                before.bodyBytes, after.bodyBytes,
                before.bodyHash, after.bodyHash,
                before.prefix64kHash, after.prefix64kHash,
                before.prefix128kHash, after.prefix128kHash,
                before.prefix192kHash, after.prefix192kHash,
                before.prefix256kHash, after.prefix256kHash,
                before.prefix384kHash, after.prefix384kHash,
                before.inputItems, after.inputItems,
                before.inputBytes, after.inputBytes,
                before.maxInputItem, after.maxInputItem,
                before.tailInputItems, after.tailInputItems,
                before.systemOrDeveloperItems, after.systemOrDeveloperItems,
                before.hasInstructions, after.hasInstructions,
                before.toolsItems, after.toolsItems,
                before.hasPromptCacheKey, after.hasPromptCacheKey,
                before.hasPromptCacheRetention, after.hasPromptCacheRetention,
                before.hasPreviousResponseId, after.hasPreviousResponseId,
                before.hasConversation, after.hasConversation,
                before.hasMetadata, after.hasMetadata,
                before.storeValue, after.storeValue,
                before.streamValue, after.streamValue);
    }

    private record CodexRequestShape(
            int bodyBytes,
            String bodyHash,
            String prefix64kHash,
            String prefix128kHash,
            String prefix192kHash,
            String prefix256kHash,
            String prefix384kHash,
            int inputItems,
            int inputBytes,
            String maxInputItem,
            String tailInputItems,
            int systemOrDeveloperItems,
            boolean hasInstructions,
            int toolsItems,
            boolean hasPromptCacheKey,
            boolean hasPromptCacheRetention,
            boolean hasPreviousResponseId,
            boolean hasConversation,
            boolean hasMetadata,
            String storeValue,
            String streamValue
    ) {
        static CodexRequestShape from(ObjectNode root, String body) {
            JsonNode input = root.get("input");
            int inputItems = input != null && input.isArray() ? input.size() : -1;
            int inputBytes = input == null ? -1 : input.toString().getBytes(StandardCharsets.UTF_8).length;
            int systemOrDeveloperItems = 0;
            int maxInputIndex = -1;
            int maxInputBytes = -1;
            String maxInputRole = "";
            String maxInputType = "";
            if (input != null && input.isArray()) {
                for (int i = 0; i < input.size(); i++) {
                    JsonNode item = input.get(i);
                    String role = item.path("role").asText("");
                    if ("system".equals(role) || "developer".equals(role)) {
                        systemOrDeveloperItems++;
                    }
                    int itemBytes = item.toString().getBytes(StandardCharsets.UTF_8).length;
                    if (itemBytes > maxInputBytes) {
                        maxInputIndex = i;
                        maxInputBytes = itemBytes;
                        maxInputRole = role;
                        maxInputType = inputItemType(item);
                    }
                }
            }
            JsonNode tools = root.get("tools");
            String maxInputItem = maxInputIndex >= 0
                    ? maxInputIndex + ":" + maxInputRole + "/" + maxInputType + "/" + maxInputBytes + "B"
                    : "none";
            return new CodexRequestShape(
                    body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length,
                    sha256Hex(body, Integer.MAX_VALUE),
                    sha256Hex(body, 64 * 1024),
                    sha256Hex(body, 128 * 1024),
                    sha256Hex(body, 192 * 1024),
                    sha256Hex(body, 256 * 1024),
                    sha256Hex(body, 384 * 1024),
                    inputItems,
                    inputBytes,
                    maxInputItem,
                    tailInputItems(input),
                    systemOrDeveloperItems,
                    !isBlankText(root.get("instructions")),
                    tools != null && tools.isArray() ? tools.size() : -1,
                    root.has("prompt_cache_key"),
                    root.has("prompt_cache_retention"),
                    root.has("previous_response_id"),
                    root.has("conversation"),
                    root.has("metadata"),
                    scalarValue(root.get("store")),
                    scalarValue(root.get("stream"))
            );
        }

        private static String scalarValue(JsonNode node) {
            if (node == null || node.isNull()) return "missing";
            if (node.isBoolean()) return String.valueOf(node.asBoolean());
            if (node.isTextual()) return "text";
            if (node.isNumber()) return "number";
            return node.getNodeType().name().toLowerCase();
        }

        private static String tailInputItems(JsonNode input) {
            if (input == null || !input.isArray() || input.isEmpty()) return "[]";
            int start = Math.max(0, input.size() - 8);
            List<String> items = new ArrayList<>();
            for (int i = start; i < input.size(); i++) {
                JsonNode item = input.get(i);
                int itemBytes = item.toString().getBytes(StandardCharsets.UTF_8).length;
                String role = item.path("role").asText("");
                String type = inputItemType(item);
                String itemHash = sha256Hex(item.toString(), Integer.MAX_VALUE);
                items.add(i + ":" + role + "/" + type + "/" + itemBytes + "B/" + itemHash);
            }
            return "[" + String.join(",", items) + "]";
        }

        private static String inputItemType(JsonNode item) {
            if (item == null || item.isNull()) return "null";
            String type = item.path("type").asText("");
            if (!type.isBlank()) return type;
            JsonNode content = item.get("content");
            if (content == null || content.isNull()) return "no_content";
            if (content.isTextual()) return "text";
            if (content.isArray()) {
                List<String> partTypes = new ArrayList<>();
                for (JsonNode part : content) {
                    String partType = part.path("type").asText("");
                    if (!partType.isBlank() && !partTypes.contains(partType)) {
                        partTypes.add(partType);
                    }
                    if (partTypes.size() >= 3) break;
                }
                return partTypes.isEmpty() ? "content_array" : String.join("+", partTypes);
            }
            return content.getNodeType().name().toLowerCase();
        }
    }

    private static String sha256Hex(String value, int maxBytes) {
        if (value == null) return "null";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(bytes.length, maxBytes);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes, 0, length);
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
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
