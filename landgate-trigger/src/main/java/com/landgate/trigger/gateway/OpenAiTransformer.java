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
import java.util.Locale;
import java.util.Map;
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
    private static final String CODEX_CLI_VERSION = "0.125.0";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** ChatGPT 内部 Codex 端点不支持的请求字段（对齐 sub2api OpenAI OAuth transform） */
    private static final Set<String> CODEX_UNSUPPORTED_FIELDS = Set.of(
            "max_output_tokens", "max_completion_tokens", "temperature", "top_p",
            "frequency_penalty", "presence_penalty", "user", "metadata",
            "prompt_cache_retention", "safety_identifier", "stream_options");

    private static final Set<String> CODEX_ALLOWED_PASSTHROUGH_HEADERS = Set.of(
            "accept",
            "accept-language",
            "conversation_id",
            "originator",
            "session_id",
            "user-agent",
            "x-codex-turn-state",
            "x-codex-turn-metadata");

    private static final Set<String> OPENAI_CHAT_RAW_ALLOWED_HEADERS = Set.of(
            "accept-language",
            "user-agent");

    private static final Map<String, String> CODEX_MODEL_MAP = Map.ofEntries(
            Map.entry("gpt-5.5", "gpt-5.5"),
            Map.entry("gpt-5.4", "gpt-5.4"),
            Map.entry("gpt-5.4-mini", "gpt-5.4-mini"),
            Map.entry("gpt-5.4-none", "gpt-5.4"),
            Map.entry("gpt-5.4-low", "gpt-5.4"),
            Map.entry("gpt-5.4-medium", "gpt-5.4"),
            Map.entry("gpt-5.4-high", "gpt-5.4"),
            Map.entry("gpt-5.4-xhigh", "gpt-5.4"),
            Map.entry("gpt-5.4-chat-latest", "gpt-5.4"),
            Map.entry("gpt-5.3", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-none", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-low", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-medium", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-high", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-xhigh", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-codex", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-codex-spark", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex-spark-low", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex-spark-medium", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex-spark-high", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex-spark-xhigh", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex-low", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-codex-medium", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-codex-high", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-codex-xhigh", "gpt-5.3-codex"),
            Map.entry("gpt-5.2", "gpt-5.2"),
            Map.entry("gpt-5.2-none", "gpt-5.2"),
            Map.entry("gpt-5.2-low", "gpt-5.2"),
            Map.entry("gpt-5.2-medium", "gpt-5.2"),
            Map.entry("gpt-5.2-high", "gpt-5.2"),
            Map.entry("gpt-5.2-xhigh", "gpt-5.2"),
            Map.entry("gpt-5", "gpt-5.4"),
            Map.entry("gpt-5-mini", "gpt-5.4"),
            Map.entry("gpt-5-nano", "gpt-5.4"),
            Map.entry("gpt-5.1", "gpt-5.4"),
            Map.entry("gpt-5.1-codex", "gpt-5.3-codex"),
            Map.entry("gpt-5.1-codex-max", "gpt-5.3-codex"),
            Map.entry("gpt-5.1-codex-mini", "gpt-5.3-codex"),
            Map.entry("gpt-5.2-codex", "gpt-5.2"),
            Map.entry("codex-mini-latest", "gpt-5.3-codex"),
            Map.entry("gpt-5-codex", "gpt-5.3-codex"));

    private static final List<Map.Entry<String, String>> CODEX_VERSION_MODEL_PREFIXES = List.of(
            Map.entry("gpt-5.3-codex-spark", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex", "gpt-5.3-codex"),
            Map.entry("gpt-5.4-mini", "gpt-5.4-mini"),
            Map.entry("gpt-5.4-nano", "gpt-5.4-nano"),
            Map.entry("gpt-5.5", "gpt-5.5"),
            Map.entry("gpt-5.4", "gpt-5.4"),
            Map.entry("gpt-5.2", "gpt-5.2"));

    @Value("${landgate.gateway.codex.preserve-prompt-cache-key:true}")
    private boolean preservePromptCacheKey = true;

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
            } else if (route.endpointKind() == EndpointKind.OPENAI_RESPONSES) {
                body = normalizeOpenAiApiKeyResponsesBody(body);
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
        if (route != null && route.normalizeCodexOAuthBody()) {
            appendCodexOAuthHeaders(headers, context, body, route);
        } else if (route != null && route.endpointKind() == EndpointKind.OPENAI_CHAT_COMPLETIONS) {
            appendOpenAiRawChatHeaders(headers, context);
        }

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
     * OpenAI API Key native Responses 请求的轻量规范化。
     * <p>
     * 对齐 sub2api OpenAI gateway：保留公共 Responses 状态字段，但移除上游不接受的
     * max_output_tokens/max_completion_tokens/prompt_cache_retention/safety_identifier；
     * 当前 HTTP 路径非 WSv2，previous_response_id 也按 sub2api 过滤。
     * 同时将 service_tier=fast 归一为 priority。
     */
    private String normalizeOpenAiApiKeyResponsesBody(String body) {
        try {
            JsonNode parsed = JSON.readTree(body);
            if (!(parsed instanceof ObjectNode root)) return body;
            normalizeOpenAIServiceTier(root);
            root.remove("max_output_tokens");
            root.remove("max_completion_tokens");
            root.remove("previous_response_id");
            root.remove("prompt_cache_retention");
            root.remove("safety_identifier");
            sanitizeEmptyBase64InputImages(root);
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Failed to normalize OpenAI API key Responses request body", e);
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

            normalizeCodexOAuthModel(root);
            normalizeCodexReasoningEffort(root);
            for (String field : CODEX_UNSUPPORTED_FIELDS) {
                if (shouldRemoveCodexField(field)) {
                    root.remove(field);
                }
            }
            normalizeCodexTextVerbosity(root);
            normalizeOpenAIServiceTier(root);
            if (isCodexCompactEndpoint(route)) {
                root.remove("store");
                root.remove("stream");
            } else {
                root.put("store", false);
                // ChatGPT Codex 内部 Responses 端点只接受流式请求；客户端非流式由网关聚合后返回。
                root.put("stream", true);
            }

            normalizeLegacyFunctionFields(root);
            normalizeCodexTools(root);
            normalizeCodexToolChoice(root);
            extractSystemMessagesToInstructions(root);
//            // 过滤上游不支持的 tool 类型（如 Codex CLI 的 namespace MCP 工具）
//            filterUnsupportedTools(root);
            if (isBlankText(root.get("instructions"))) {
                root.put("instructions", "You are a helpful coding assistant.");
            }
            normalizeCodexInput(root);
            sanitizeEmptyBase64InputImages(root);

            String normalized = JSON.writeValueAsString(root);
            CodexRequestShape afterShape = CodexRequestShape.from(root, normalized);
            logCodexNormalizationDiagnostics(requestId, account, route, beforeShape, afterShape);
            return normalized;
        } catch (Exception e) {
            log.warn("Failed to normalize Codex OAuth request body: account_id={}", account.getId(), e);
            return body;
        }
    }

    /**
     * 兼容旧 OpenAI Chat Completions 的 functions/function_call 字段。
     * ChatGPT internal Codex 只接受 Responses 风格 tools/tool_choice。
     */
    private static void normalizeLegacyFunctionFields(ObjectNode root) {
        JsonNode functions = root.get("functions");
        if (functions != null && functions.isArray()) {
            ArrayNode tools = root.has("tools") && root.get("tools").isArray()
                    ? (ArrayNode) root.get("tools")
                    : JSON.createArrayNode();
            for (JsonNode function : functions) {
                if (function != null && function.isObject()) {
                    ObjectNode tool = JSON.createObjectNode();
                    tool.put("type", "function");
                    tool.set("function", function);
                    tools.add(tool);
                }
            }
            root.set("tools", tools);
            root.remove("functions");
        }

        JsonNode functionCall = root.get("function_call");
        if (functionCall == null || functionCall.isNull()) return;
        if (functionCall.isTextual()) {
            String choice = functionCall.asText().trim();
            if (!choice.isBlank()) {
                root.put("tool_choice", choice);
            }
        } else if (functionCall.isObject() && !isBlankText(functionCall.get("name"))) {
            ObjectNode choice = JSON.createObjectNode();
            choice.put("type", "function");
            choice.put("name", functionCall.get("name").asText());
            root.set("tool_choice", choice);
        }
        root.remove("function_call");
    }

    /**
     * 归一化 Responses tools：
     * <ul>
     *   <li>{"type":"function","function":{...}} 展平为 Codex internal 可接受的 function tool。</li>
     *   <li>input_schema 兼容映射到 parameters。</li>
     *   <li>object schema 缺 properties 时补空对象。</li>
     * </ul>
     */
    private static void normalizeCodexTools(ObjectNode root) {
        JsonNode toolsNode = root.get("tools");
        if (toolsNode == null || !toolsNode.isArray()) return;

        ArrayNode normalized = JSON.createArrayNode();
        for (JsonNode rawTool : toolsNode) {
            if (!(rawTool instanceof ObjectNode tool)) {
                continue;
            }
            String type = textValue(tool.get("type"));
            if (!"function".equals(type)) {
                normalized.add(tool);
                continue;
            }

            ObjectNode out = tool.deepCopy();
            JsonNode function = out.get("function");
            if (function != null && function.isObject()) {
                if (isBlankText(out.get("name")) && !isBlankText(function.get("name"))) {
                    out.put("name", function.get("name").asText());
                }
                if (isBlankText(out.get("description")) && !isBlankText(function.get("description"))) {
                    out.put("description", function.get("description").asText());
                }
                if (!out.has("parameters") && function.has("parameters")) {
                    out.set("parameters", function.get("parameters"));
                }
                if (!out.has("strict") && function.has("strict")) {
                    out.set("strict", function.get("strict"));
                }
            }
            if (!out.has("parameters") && out.has("input_schema")) {
                out.set("parameters", out.get("input_schema"));
                out.remove("input_schema");
            }
            if (isBlankText(out.get("name"))) {
                continue;
            }
            out.set("parameters", normalizeFunctionParameters(out.get("parameters")));
            normalized.add(out);
        }
        root.set("tools", normalized);
    }

    private static JsonNode normalizeFunctionParameters(JsonNode parameters) {
        if (!(parameters instanceof ObjectNode object)) {
            ObjectNode empty = JSON.createObjectNode();
            empty.put("type", "object");
            empty.set("properties", JSON.createObjectNode());
            return empty;
        }
        ObjectNode copy = object.deepCopy();
        if ("object".equals(textValue(copy.get("type"))) && !copy.has("properties")) {
            copy.set("properties", JSON.createObjectNode());
        }
        return copy;
    }

    /**
     * Codex internal 对 tool_choice 很挑剔：function choice 必须有 name，且 name
     * 必须在 tools 中存在；否则回退 auto，避免上游 400。
     */
    private static void normalizeCodexToolChoice(ObjectNode root) {
        JsonNode choice = root.get("tool_choice");
        if (choice == null || choice.isNull()) return;
        if (choice.isTextual()) {
            String value = choice.asText().trim();
            if (!Set.of("auto", "required", "none").contains(value)
                    && !codexToolsContainType(root.get("tools"), value)) {
                root.put("tool_choice", "auto");
            }
            return;
        }
        if (!(choice instanceof ObjectNode choiceObject)) {
            root.put("tool_choice", "auto");
            return;
        }

        String type = textValue(choiceObject.get("type"));
        if ("function".equals(type)) {
            String name = textValue(choiceObject.get("name"));
            JsonNode function = choiceObject.get("function");
            if (name.isBlank() && function != null && function.isObject()) {
                name = textValue(function.get("name"));
            }
            if (name.isBlank() || !codexToolsContainFunctionName(root.get("tools"), name)) {
                root.put("tool_choice", "auto");
                return;
            }
            ObjectNode normalized = JSON.createObjectNode();
            normalized.put("type", "function");
            normalized.put("name", name);
            root.set("tool_choice", normalized);
            return;
        }
        if (!codexToolsContainType(root.get("tools"), type)) {
            root.put("tool_choice", "auto");
        }
    }

    private static boolean codexToolsContainType(JsonNode tools, String type) {
        if (tools == null || !tools.isArray() || type == null || type.isBlank()) return false;
        for (JsonNode tool : tools) {
            if (type.equals(textValue(tool.get("type")))) {
                return true;
            }
        }
        return false;
    }

    private static boolean codexToolsContainFunctionName(JsonNode tools, String name) {
        if (tools == null || !tools.isArray() || name == null || name.isBlank()) return false;
        for (JsonNode tool : tools) {
            if (!"function".equals(textValue(tool.get("type")))) {
                continue;
            }
            String toolName = textValue(tool.get("name"));
            JsonNode function = tool.get("function");
            if (toolName.isBlank() && function != null && function.isObject()) {
                toolName = textValue(function.get("name"));
            }
            if (name.equals(toolName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Codex internal 不接受 role:"tool" 的 message item，也不接受 input 直接为字符串。
     * 对齐 sub2api：转换为 function_call_output，content.text 非字符串时字符串化。
     */
    private static void normalizeCodexInput(ObjectNode root) {
        JsonNode inputNode = root.get("input");
        if (inputNode == null || inputNode.isNull()) return;

        if (inputNode.isTextual()) {
            String input = inputNode.asText();
            ArrayNode items = JSON.createArrayNode();
            if (!input.trim().isEmpty()) {
                ObjectNode item = JSON.createObjectNode();
                item.put("type", "message");
                item.put("role", "user");
                item.put("content", input);
                items.add(item);
            }
            root.set("input", items);
            return;
        }

        if (!inputNode.isArray()) return;
        ArrayNode normalized = JSON.createArrayNode();
        boolean preserveReferences = needsToolContinuation(root);
        for (JsonNode item : inputNode) {
            if (!(item instanceof ObjectNode objectItem)) {
                normalized.add(item);
                continue;
            }
            if ("tool".equals(textValue(objectItem.get("role")))) {
                JsonNode converted = convertToolRoleItem(objectItem);
                if (converted instanceof ObjectNode convertedObject) {
                    normalized.add(filterCodexInputItem(convertedObject, preserveReferences));
                } else {
                    normalized.add(converted);
                }
                continue;
            }
            JsonNode contentNormalized = normalizeMessageContentText(objectItem);
            if (contentNormalized instanceof ObjectNode normalizedObject) {
                JsonNode filtered = filterCodexInputItem(normalizedObject, preserveReferences);
                if (filtered != null) {
                    normalized.add(filtered);
                }
            } else {
                normalized.add(contentNormalized);
            }
        }
        root.set("input", normalized);
    }

    private static JsonNode filterCodexInputItem(ObjectNode item, boolean preserveReferences) {
        String type = textValue(item.get("type"));

        // ChatGPT Codex internal runs with store=false; replayed reasoning references
        // are not persisted upstream and can cause "Item with id rs_* not found".
        if ("reasoning".equals(type)) {
            return null;
        }

        if ("item_reference".equals(type)) {
            if (!preserveReferences) {
                return null;
            }
            ObjectNode copy = item.deepCopy();
            String id = textValue(copy.get("id"));
            if (id.startsWith("call_")) {
                copy.put("id", fixCodexCallIdPrefix(id));
            }
            return copy;
        }

        ObjectNode copy = item.deepCopy();
        if (isCodexToolCallItemType(type)) {
            String callId = textValue(copy.get("call_id"));
            if (callId.isBlank()) {
                String id = textValue(copy.get("id"));
                if (!id.isBlank()) {
                    callId = id;
                    copy.put("call_id", callId);
                }
            }
            if (!callId.isBlank()) {
                copy.put("call_id", fixCodexCallIdPrefix(callId));
            }
        } else {
            copy.remove("call_id");
        }

        if (codexInputItemRequiresName(type) && textValue(copy.get("name")).isBlank()) {
            String name = firstNonBlank(textValue(copy.get("tool_name")));
            JsonNode function = copy.get("function");
            if (name.isBlank() && function != null && function.isObject()) {
                name = textValue(function.get("name"));
            }
            copy.put("name", name.isBlank() ? "tool" : name);
        }

        if (!preserveReferences) {
            copy.remove("id");
        }
        return copy;
    }

    private static boolean needsToolContinuation(ObjectNode root) {
        if (root == null) return false;
        if (!textValue(root.get("previous_response_id")).isBlank()) {
            return true;
        }
        JsonNode tools = root.get("tools");
        if (tools != null && tools.isArray() && !tools.isEmpty()) {
            return true;
        }
        JsonNode toolChoice = root.get("tool_choice");
        if (toolChoice != null && !toolChoice.isNull()) {
            return true;
        }
        JsonNode input = root.get("input");
        if (input == null || !input.isArray()) {
            return false;
        }
        for (JsonNode item : input) {
            if (!(item instanceof ObjectNode objectItem)) {
                continue;
            }
            String type = textValue(objectItem.get("type"));
            if ("item_reference".equals(type) || isCodexToolCallItemType(type)) {
                return true;
            }
            if ("tool".equals(textValue(objectItem.get("role")))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCodexToolCallItemType(String type) {
        return switch (type == null ? "" : type.trim()) {
            case "function_call",
                    "tool_call",
                    "local_shell_call",
                    "tool_search_call",
                    "custom_tool_call",
                    "mcp_tool_call",
                    "function_call_output",
                    "mcp_tool_call_output",
                    "custom_tool_call_output",
                    "tool_search_output" -> true;
            default -> false;
        };
    }

    private static boolean codexInputItemRequiresName(String type) {
        return switch (type == null ? "" : type.trim()) {
            case "function_call", "custom_tool_call", "mcp_tool_call" -> true;
            default -> false;
        };
    }

    private static String fixCodexCallIdPrefix(String id) {
        String value = id == null ? "" : id.trim();
        if (value.isBlank() || value.startsWith("fc")) {
            return value;
        }
        if (value.startsWith("call_")) {
            return "fc" + value.substring("call_".length());
        }
        return "fc_" + value;
    }

    private static JsonNode convertToolRoleItem(ObjectNode item) {
        String callId = firstNonBlank(
                textValue(item.get("call_id")),
                textValue(item.get("tool_call_id")),
                textValue(item.get("id")));
        if (callId.isBlank()) {
            ObjectNode fallback = item.deepCopy();
            fallback.put("role", "user");
            fallback.remove("tool_call_id");
            return normalizeMessageContentText(fallback);
        }

        ObjectNode output = JSON.createObjectNode();
        output.put("type", "function_call_output");
        output.put("call_id", callId);
        String text = extractTextFromContent(item.get("content"));
        if (text.isBlank() && item.has("output")) {
            text = textValue(item.get("output"));
        }
        if (text.isBlank() && item.has("content")) {
            text = item.get("content").toString();
        }
        output.put("output", text);
        return output;
    }

    private static JsonNode normalizeMessageContentText(ObjectNode item) {
        if (!"message".equals(textValue(item.get("type"))) || !item.has("content") || !item.get("content").isArray()) {
            return item;
        }
        ObjectNode normalized = item.deepCopy();
        ArrayNode parts = JSON.createArrayNode();
        for (JsonNode part : normalized.get("content")) {
            if (part instanceof ObjectNode partObject && partObject.has("text")
                    && !partObject.get("text").isTextual()) {
                ObjectNode normalizedPart = partObject.deepCopy();
                normalizedPart.put("text", stringifyJsonValue(partObject.get("text")));
                parts.add(normalizedPart);
            } else {
                parts.add(part);
            }
        }
        normalized.set("content", parts);
        return normalized;
    }

    private static void normalizeOpenAIServiceTier(ObjectNode root) {
        JsonNode value = root.get("service_tier");
        if (value == null || !value.isTextual()) {
            return;
        }
        String normalized = normalizedOpenAIServiceTierValue(value.asText());
        if (normalized.isBlank()) {
            root.remove("service_tier");
        } else {
            root.put("service_tier", normalized);
        }
    }

    private static String normalizedOpenAIServiceTierValue(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase();
        if (value.isBlank()) return "";
        if ("fast".equals(value)) {
            value = "priority";
        }
        return switch (value) {
            case "priority", "flex", "auto", "default", "scale" -> value;
            default -> "";
        };
    }

    private static void normalizeCodexOAuthModel(ObjectNode root) {
        JsonNode modelNode = root.get("model");
        if (modelNode == null || !modelNode.isTextual()) {
            return;
        }
        root.put("model", normalizeCodexModel(modelNode.asText()));
    }

    private static String normalizeCodexModel(String model) {
        String trimmed = model == null ? "" : model.trim();
        if (trimmed.isEmpty()) {
            return "gpt-5.4";
        }
        String known = normalizeKnownCodexModel(trimmed);
        return known.isBlank() ? trimmed : known;
    }

    private static String normalizeKnownCodexModel(String model) {
        String trimmed = model == null ? "" : model.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (isOpenAIImageGenerationModel(trimmed)) {
            return trimmed;
        }

        String modelId = lastOpenAIModelSegment(trimmed);
        String canonical = canonicalizeOpenAIModelAliasSpelling(modelId);
        if (!canonical.isBlank()) {
            modelId = canonical;
        }

        String mapped = normalizeKnownOpenAICodexModel(modelId);
        if (!mapped.isBlank()) {
            return mapped;
        }

        String key = codexModelLookupKey(modelId);
        if (key.isBlank()) {
            return "";
        }
        mapped = CODEX_MODEL_MAP.getOrDefault(key, "");
        if (!mapped.isBlank()) {
            return mapped;
        }
        for (Map.Entry<String, String> prefix : CODEX_VERSION_MODEL_PREFIXES) {
            if (key.equals(prefix.getKey())) {
                return prefix.getValue();
            }
            String prefixWithDash = prefix.getKey() + "-";
            if (key.startsWith(prefixWithDash)
                    && isKnownCodexModelSuffix(key.substring(prefixWithDash.length()))) {
                return prefix.getValue();
            }
        }
        return "";
    }

    private static String normalizeKnownOpenAICodexModel(String model) {
        String normalized = canonicalizeOpenAIModelAliasSpelling(model);
        if (normalized.isBlank()) {
            return "";
        }

        String mapped = CODEX_MODEL_MAP.getOrDefault(codexModelLookupKey(normalized), "");
        if (!mapped.isBlank()) {
            return mapped;
        }
        if (normalized.endsWith("-openai-compact")) {
            mapped = CODEX_MODEL_MAP.getOrDefault(
                    codexModelLookupKey(normalized.substring(0, normalized.length() - "-openai-compact".length())), "");
            if (!mapped.isBlank()) {
                return mapped;
            }
        }

        if (normalized.contains("gpt-5.5")) return "gpt-5.5";
        if (normalized.contains("gpt-5.4-mini")) return "gpt-5.4-mini";
        if (normalized.contains("gpt-5.4-nano")) return "gpt-5.4-nano";
        if (normalized.contains("gpt-5.4")) return "gpt-5.4";
        if (normalized.contains("gpt-5.2")) return "gpt-5.2";
        if (normalized.contains("gpt-5.3-codex-spark")) return "gpt-5.3-codex-spark";
        if (normalized.contains("gpt-5.3-codex")) return "gpt-5.3-codex";
        if (normalized.contains("gpt-5.3")) return "gpt-5.3-codex";
        if (normalized.contains("codex")) return "gpt-5.3-codex";
        if (normalized.contains("gpt-5")) return "gpt-5.4";
        return "";
    }

    private static String canonicalizeOpenAIModelAliasSpelling(String model) {
        String normalized = lastOpenAIModelSegment(model).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }

        normalized = normalized.replace('_', '-');
        normalized = String.join("-", normalized.trim().split("\\s+"));
        while (normalized.contains("--")) {
            normalized = normalized.replace("--", "-");
        }
        if (normalized.startsWith("gpt5")) {
            normalized = "gpt-5" + normalized.substring("gpt5".length());
        }
        if (!normalized.startsWith("gpt-") && !normalized.contains("codex")) {
            return "";
        }

        normalized = normalized.replace("gpt-5.4mini", "gpt-5.4-mini");
        normalized = normalized.replace("gpt-5.4nano", "gpt-5.4-nano");
        normalized = normalized.replace("gpt-5.3-codexspark", "gpt-5.3-codex-spark");
        normalized = normalized.replace("gpt-5.3codexspark", "gpt-5.3-codex-spark");
        normalized = normalized.replace("gpt-5.3codex", "gpt-5.3-codex");
        return normalized;
    }

    private static String lastOpenAIModelSegment(String model) {
        String value = model == null ? "" : model.trim();
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1).trim() : value;
    }

    private static String codexModelLookupKey(String modelId) {
        String value = modelId == null ? "" : modelId.trim();
        if (value.isBlank()) {
            return "";
        }
        value = lastOpenAIModelSegment(value);
        return String.join("-", value.toLowerCase(Locale.ROOT).trim().split("\\s+"));
    }

    private static boolean isKnownCodexModelSuffix(String suffix) {
        return switch (suffix) {
            case "none", "minimal", "low", "medium", "high", "xhigh" -> true;
            default -> isCodexDateSuffix(suffix);
        };
    }

    private static boolean isCodexDateSuffix(String suffix) {
        if (suffix == null) return false;
        String[] parts = suffix.split("-");
        if (parts.length != 3 || parts[0].length() != 4 || parts[1].length() != 2 || parts[2].length() != 2) {
            return false;
        }
        for (String part : parts) {
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isOpenAIImageGenerationModel(String model) {
        return model != null && model.trim().toLowerCase(Locale.ROOT).startsWith("gpt-image-");
    }

    private static void normalizeCodexReasoningEffort(ObjectNode root) {
        JsonNode reasoning = root.get("reasoning");
        if (reasoning instanceof ObjectNode reasoningObject
                && "minimal".equals(textValue(reasoningObject.get("effort")))) {
            reasoningObject.put("effort", "none");
        }
    }

    private static void normalizeCodexTextVerbosity(ObjectNode root) {
        String model = textValue(root.get("model"));
        if (supportsVerbosity(model)) {
            return;
        }
        JsonNode text = root.get("text");
        if (text instanceof ObjectNode textObject) {
            textObject.remove("verbosity");
        }
    }

    private static boolean supportsVerbosity(String model) {
        String value = model == null ? "" : model.trim();
        if (!value.startsWith("gpt-")) {
            return true;
        }

        String version = value.substring("gpt-".length());
        int majorEnd = leadingDigitsEnd(version, 0);
        if (majorEnd == 0) {
            return true;
        }
        int major = parseIntOrDefault(version.substring(0, majorEnd), 0);
        if (major > 5) {
            return true;
        }
        if (major < 5) {
            return false;
        }
        if (majorEnd >= version.length() || version.charAt(majorEnd) != '.') {
            return true;
        }

        int minorStart = majorEnd + 1;
        int minorEnd = leadingDigitsEnd(version, minorStart);
        int minor = minorEnd > minorStart
                ? parseIntOrDefault(version.substring(minorStart, minorEnd), 0)
                : 0;
        return minor >= 3;
    }

    private static int leadingDigitsEnd(String value, int start) {
        int i = start;
        while (i < value.length() && Character.isDigit(value.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int parseIntOrDefault(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void sanitizeEmptyBase64InputImages(ObjectNode root) {
        JsonNode input = root.get("input");
        if (input == null || !input.isArray()) {
            return;
        }
        ArrayNode normalizedItems = JSON.createArrayNode();
        boolean changed = false;
        for (JsonNode item : input) {
            if (!(item instanceof ObjectNode itemObject)) {
                normalizedItems.add(item);
                continue;
            }
            if (shouldDropEmptyBase64InputImagePart(itemObject)) {
                changed = true;
                continue;
            }
            JsonNode content = itemObject.get("content");
            if (content == null || !content.isArray()) {
                normalizedItems.add(item);
                continue;
            }
            ArrayNode normalizedParts = JSON.createArrayNode();
            boolean itemChanged = false;
            for (JsonNode part : content) {
                if (part instanceof ObjectNode partObject && shouldDropEmptyBase64InputImagePart(partObject)) {
                    changed = true;
                    itemChanged = true;
                    continue;
                }
                normalizedParts.add(part);
            }
            if (itemChanged) {
                if (normalizedParts.isEmpty()) {
                    continue;
                }
                ObjectNode copied = itemObject.deepCopy();
                copied.set("content", normalizedParts);
                normalizedItems.add(copied);
            } else {
                normalizedItems.add(item);
            }
        }
        if (changed) {
            root.set("input", normalizedItems);
        }
    }

    private static boolean shouldDropEmptyBase64InputImagePart(ObjectNode part) {
        return "input_image".equals(textValue(part.get("type")))
                && isEmptyBase64DataURI(textValue(part.get("image_url")));
    }

    private static boolean isEmptyBase64DataURI(String raw) {
        if (raw == null || !raw.startsWith("data:")) return false;
        String rest = raw.substring("data:".length());
        int semicolon = rest.indexOf(';');
        if (semicolon < 0) return false;
        rest = rest.substring(semicolon + 1);
        if (!rest.startsWith("base64,")) return false;
        return rest.substring("base64,".length()).trim().isEmpty();
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
                    String text = stringifyJsonValue(part.get("text"));
                    if (!text.isBlank()) texts.add(text);
                }
            }
            return String.join("\n", texts);
        }
        return contentNode.asText();
    }

    private static String textValue(JsonNode node) {
        return node != null && node.isTextual() ? node.asText().trim() : "";
    }

    private static String stringifyJsonValue(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        return node.toString();
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

    private static void appendCodexOAuthHeaders(List<String> headers, UpstreamRequestContext context,
                                                String normalizedBody, UpstreamRoute route) {
        if (context == null || headers == null) return;
        var requestHeaders = context.requestHeaders();
        String chatgptAccountId = credentialText(context.account(), "chatgpt_account_id");
        if (!chatgptAccountId.isBlank()) {
            headers.add("chatgpt-account-id");
            headers.add(chatgptAccountId);
        }
        if (requestHeaders != null) {
            requestHeaders.forEach((key, value) -> {
                String normalizedKey = key == null ? "" : key.trim().toLowerCase();
                if (normalizedKey.isBlank() || value == null || value.isBlank()) return;
                if (CODEX_ALLOWED_PASSTHROUGH_HEADERS.contains(normalizedKey)) {
                    headers.add(key);
                    headers.add(value);
                }
            });
        }

        removeHeader(headers, "conversation_id");
        removeHeader(headers, "session_id");

        String promptCacheKey = extractPromptCacheKey(normalizedBody);
        String clientSession = firstNonBlank(
                headerValue(requestHeaders, "session_id"),
                headerValue(requestHeaders, "conversation_id"),
                promptCacheKey);
        if (!clientSession.isBlank()) {
            String isolated = isolateCodexSessionId(context.apiKeyId(), clientSession);
            headers.add("session_id");
            headers.add(isolated);
            headers.add("conversation_id");
            headers.add(isolated);
        }
        if (isCodexCompactEndpoint(route)) {
            removeHeader(headers, "Accept");
            headers.add("Accept");
            headers.add("application/json");
            if (getHeader(headers, "Version").isBlank()) {
                headers.add("Version");
                headers.add(CODEX_CLI_VERSION);
            }
        } else if (getHeader(headers, "Accept").isBlank()) {
            headers.add("Accept");
            headers.add("text/event-stream");
        }
        if (getHeader(headers, "OpenAI-Beta").isBlank()) {
            headers.add("OpenAI-Beta");
            headers.add("responses=experimental");
        }
        if (getHeader(headers, "Originator").isBlank()) {
            headers.add("Originator");
            headers.add("codex_cli_rs");
        }
    }

    private static void appendOpenAiRawChatHeaders(List<String> headers, UpstreamRequestContext context) {
        if (context == null || headers == null) return;
        if (context.stream()) {
            headers.add("Accept");
            headers.add("text/event-stream");
        } else {
            headers.add("Accept");
            headers.add("application/json");
        }
        var requestHeaders = context.requestHeaders();
        if (requestHeaders == null) return;
        requestHeaders.forEach((key, value) -> {
            String normalizedKey = key == null ? "" : key.trim().toLowerCase();
            if (normalizedKey.isBlank() || value == null || value.isBlank()) return;
            if (OPENAI_CHAT_RAW_ALLOWED_HEADERS.contains(normalizedKey)) {
                removeHeader(headers, key);
                headers.add(key);
                headers.add(value);
            }
        });
    }

    private static String credentialText(AccountEntity account, String field) {
        if (account == null || account.getCredentials() == null || account.getCredentials().isBlank()
                || field == null || field.isBlank()) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(account.getCredentials());
            JsonNode value = root.get(field);
            return value != null && value.isTextual() ? value.asText().trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String extractPromptCacheKey(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode value = root.get("prompt_cache_key");
            return value != null && value.isTextual() ? value.asText().trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String headerValue(Map<String, String> headers, String name) {
        if (headers == null || name == null) return "";
        for (var entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue() == null ? "" : entry.getValue().trim();
            }
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static String isolateCodexSessionId(Long apiKeyId, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) return "";
        long key = apiKeyId == null ? 0L : apiKeyId;
        return fnv1a64Hex("k" + key + ":" + value);
    }

    private static String fnv1a64Hex(String value) {
        long hash = 0xcbf29ce484222325L;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= (b & 0xffL);
            hash *= 0x100000001b3L;
        }
        return String.format("%016x", hash);
    }

    private static void removeHeader(List<String> headers, String name) {
        if (headers == null || name == null) return;
        for (int i = 0; i + 1 < headers.size(); ) {
            if (headers.get(i).equalsIgnoreCase(name)) {
                headers.remove(i + 1);
                headers.remove(i);
            } else {
                i += 2;
            }
        }
    }

    private static String getHeader(List<String> headers, String name) {
        if (headers == null || name == null) return "";
        for (int i = 0; i + 1 < headers.size(); i += 2) {
            if (headers.get(i).equalsIgnoreCase(name)) {
                return headers.get(i + 1);
            }
        }
        return "";
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
