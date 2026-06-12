package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * OpenAI Responses API（IR）→ Anthropic Messages API 转换器。
 * <p>
 * 负责请求反向转换、非流式响应反向转换、流式 SSE 反向翻译。
 * 由 {@link AnthropicConverter} 门面委托调用，不作为独立 Spring Bean。
 *
 * <p>参照：sub2api {@code responses_to_anthropic.go} + {@code responses_to_anthropic_request.go}
 */
@Slf4j
public class ResponsesToAnthropicConverter {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ========================
    // 请求反向转换：Responses IR → Anthropic
    // ========================

    /**
     * 将 Responses API 请求（IR）转换为 Anthropic Messages 请求。
     *
     * @param ir Responses 格式的 JsonNode
     * @return Anthropic 请求 JSON 字符串
     */
    public String requestFromIR(JsonNode ir) {
        try {
            ObjectNode dst = JSON.createObjectNode();

            copyTextIfExists(ir, dst, "model");
            copyBooleanIfExists(ir, dst, "stream");
            copyNumberIfExists(ir, dst, "temperature");
            copyNumberIfExists(ir, dst, "top_p");

            // Anthropic max_tokens is required; if Responses omits it or sends an invalid value,
            // use the gateway default instead of forwarding a malformed limit.
            dst.put("max_tokens", isPositiveInt(ir.get("max_output_tokens"))
                    ? ir.get("max_output_tokens").asInt()
                    : 8192);

            // input[] → system + messages[]
            String systemText = null;
            if (ir.has("instructions") && !isBlankText(ir.get("instructions"))) {
                systemText = appendSystemText(systemText, ir.get("instructions").asText());
            }
            List<JsonNode> inputItems = new ArrayList<>();
            if (ir.has("input")) {
                JsonNode inputNode = ir.get("input");
                if (inputNode.isArray()) {
                    for (JsonNode item : inputNode) {
                        String role = textOrDefault(item.get("role"), null);
                        String itemType = textOrDefault(item.get("type"), null);

                        // system/developer role → 提取到 Anthropic 顶层 system
                        if ("system".equals(role) || "developer".equals(role)) {
                            String text = extractTextFromContent(item.get("content"));
                            if (text != null && !text.isEmpty()) {
                                systemText = appendSystemText(systemText, text);
                            }
                            continue;
                        }

                        // function_call → assistant message with tool_use
                        if ("function_call".equals(itemType)) {
                            JsonNode assistantMsg = convertFunctionCallToAssistantMsg(item);
                            if (assistantMsg != null) {
                                inputItems.add(assistantMsg);
                            }
                            continue;
                        }

                        // function_call_output → user message with tool_result
                        if ("function_call_output".equals(itemType)) {
                            JsonNode toolResultMsg = convertFunctionCallOutputToUserMsg(item);
                            if (toolResultMsg != null) {
                                inputItems.add(toolResultMsg);
                            }
                            continue;
                        }

                        if ("reasoning".equals(itemType)) {
                            inputItems.add(convertReasoningToAssistantMsg(item));
                            continue;
                        }

                        // 普通 message item
                        inputItems.add(item);
                    }
                } else if (inputNode.isTextual()) {
                    // 字符串 input（如 "hello"）→ 转为单条 user message
                    ObjectNode userMsg = JSON.createObjectNode();
                    userMsg.put("role", "user");
                    userMsg.put("content", inputNode.asText());
                    inputItems.add(userMsg);
                }
            }

            // messages 数组构建
            ArrayNode messages = JSON.createArrayNode();
            for (JsonNode item : inputItems) {
                String role = textOrDefault(item.get("role"), null);
                if (role == null) continue;

                switch (role) {
                    case "user" -> {
                        JsonNode content = convertUserContentToAnthropic(item.get("content"));
                        if (content != null) {
                            ObjectNode msg = JSON.createObjectNode();
                            msg.put("role", "user");
                            msg.set("content", content);
                            messages.add(msg);
                        }
                    }
                    case "assistant" -> {
                        JsonNode content = convertAssistantContentToAnthropic(item.get("content"));
                        if (content != null) {
                            ObjectNode msg = JSON.createObjectNode();
                            msg.put("role", "assistant");
                            msg.set("content", content);
                            messages.add(msg);
                        }
                    }
                    default -> {
                        // 未知 role → user
                        JsonNode content = convertUserContentToAnthropic(item.get("content"));
                        if (content != null) {
                            ObjectNode msg = JSON.createObjectNode();
                            msg.put("role", "user");
                            msg.set("content", content);
                            messages.add(msg);
                        }
                    }
                }
            }

            // 合并连续同角色消息（Anthropic 要求 user/assistant 交替）
            messages = mergeConsecutiveMessages(messages);

            // system
            if (systemText != null && !systemText.isEmpty()) {
                dst.put("system", systemText);
            }
            dst.set("messages", messages);

            // --- tools ---
            if (ir.has("tools") && ir.get("tools").isArray()) {
                ArrayNode anthropicTools = JSON.createArrayNode();
                for (JsonNode tool : ir.get("tools")) {
                    JsonNode convertedTool = convertResponsesToolToAnthropic(tool);
                    if (convertedTool != null) {
                        anthropicTools.add(convertedTool);
                    }
                }
                if (anthropicTools.size() > 0) {
                    dst.set("tools", anthropicTools);
                }
            }

            // --- tool_choice ---
            if (ir.has("tool_choice")) {
                JsonNode toolChoice = convertResponsesToolChoiceToAnthropic(ir.get("tool_choice"));
                if (toolChoice != null) {
                    dst.set("tool_choice", toolChoice);
                }
            }

            // --- reasoning.effort → output_config.effort + thinking ---
            if (ir.has("reasoning") && ir.get("reasoning").has("effort")) {
                String anthropicEffort = mapResponsesEffortToAnthropic(ir.get("reasoning").get("effort"));
                if (anthropicEffort != null) {
                    ObjectNode outputConfig = JSON.createObjectNode();
                    outputConfig.put("effort", anthropicEffort);
                    dst.set("output_config", outputConfig);
                }

                if (anthropicEffort != null && !"low".equals(anthropicEffort)) {
                    ObjectNode thinking = JSON.createObjectNode();
                    thinking.put("type", "enabled");
                    thinking.put("budget_tokens", defaultThinkingBudget(anthropicEffort));
                    dst.set("thinking", thinking);
                }
            }

            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Responses→Anthropic requestFromIR error: {}", e.getMessage());
            return "{}";
        }
    }

    // ========================
    // 非流式响应反向转换：Responses IR → Anthropic
    // ========================

    /**
     * 将 Responses 非流式响应（IR）转换为 Anthropic 响应。
     */
    public String responseFromIR(JsonNode ir) {
        try {
            ObjectNode dst = JSON.createObjectNode();

            dst.put("id", textOrDefault(ir.get("id"),
                    "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24)));
            dst.put("type", "message");
            dst.put("role", "assistant");
            dst.put("model", textOrDefault(ir.get("model"), "unknown"));

            // output[] → content[]
            ArrayNode content = JSON.createArrayNode();
            boolean hasToolUse = false;

            if (ir.has("output") && ir.get("output").isArray()) {
                for (JsonNode item : ir.get("output")) {
                    String itemType = textOrDefault(item.get("type"), "");

                    switch (itemType) {
                        case "reasoning" -> {
                            addResponseReasoningBlocks(content, item);
                        }
                        case "message" -> {
                            JsonNode msgContent = item.get("content");
                            if (msgContent != null && msgContent.isArray()) {
                                for (JsonNode part : msgContent) {
                                    String partType = textOrDefault(part.get("type"), "");
                                    String text = textOrDefault(part.get("text"), "");
                                    String refusal = textOrDefault(part.get("refusal"), "");
                                    if ("output_text".equals(partType) && !text.isEmpty()) {
                                        ObjectNode textBlock = JSON.createObjectNode();
                                        textBlock.put("type", "text");
                                        textBlock.put("text", text);
                                        content.add(textBlock);
                                    } else if ("refusal".equals(partType) && !refusal.isEmpty()) {
                                        ObjectNode textBlock = JSON.createObjectNode();
                                        textBlock.put("type", "text");
                                        textBlock.put("text", refusal);
                                        content.add(textBlock);
                                    }
                                }
                            }
                        }
                        case "function_call" -> {
                            if (isBlankText(item.get("call_id")) || isBlankText(item.get("name"))) {
                                log.debug("Responses→Anthropic: output function_call missing call_id or name, ignored");
                                continue;
                            }
                            hasToolUse = true;
                            ObjectNode toolUse = JSON.createObjectNode();
                            toolUse.put("type", "tool_use");
                            toolUse.put("id", fromResponsesCallID(item.get("call_id").asText()));
                            toolUse.put("name", item.get("name").asText());
                            String args = textOrDefault(item.get("arguments"), "{}");
                            args = sanitizeAnthropicToolUseInput(item.get("name").asText(), args);
                            try {
                                toolUse.set("input", JSON.readTree(args));
                            } catch (Exception e) {
                                toolUse.put("input", args);
                            }
                            content.add(toolUse);
                        }
                        case "web_search_call" -> {
                            addWebSearchBlocks(content, item);
                        }
                        default ->
                            log.debug("Responses→Anthropic: unknown output type '{}'", itemType);
                    }
                }
            }

            if (content.size() == 0) {
                ObjectNode emptyText = JSON.createObjectNode();
                emptyText.put("type", "text");
                emptyText.put("text", "");
                content.add(emptyText);
            }
            dst.set("content", content);

            // stop_reason
            String status = textOrDefault(ir.get("status"), "completed");
            String stopReason = mapResponsesStatusToAnthropicStopReason(status, ir, hasToolUse);
            dst.put("stop_reason", stopReason);

            // usage
            if (ir.has("usage")) {
                JsonNode usage = ir.get("usage");
                int inputTokens = nonNegativeIntOrZero(usage.get("input_tokens"));
                int outputTokens = nonNegativeIntOrZero(usage.get("output_tokens"));
                int cachedTokens = nonNegativeIntOrZero(usage.path("input_tokens_details").get("cached_tokens"));

                // Anthropic input_tokens 不包含缓存 token（减去 cached_tokens，最小为 0）
                int anthropicInputTokens = Math.max(inputTokens - cachedTokens, 0);
                ObjectNode anthropicUsage = JSON.createObjectNode();
                anthropicUsage.put("input_tokens", anthropicInputTokens);
                anthropicUsage.put("output_tokens", outputTokens);
                if (cachedTokens > 0) {
                    anthropicUsage.put("cache_read_input_tokens", cachedTokens);
                }
                dst.set("usage", anthropicUsage);
            }

            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Responses→Anthropic responseFromIR error: {}", e.getMessage());
            return "{}";
        }
    }

    // ========================
    // 流式 SSE 翻译：Responses SSE（IR）→ Anthropic SSE
    // ========================

    /**
     * 创建 Responses SSE → Anthropic SSE 流式翻译器。
     */
    public StreamTranslator createStreamFromIR(String model) {
        return new IRToAnthropicStreamTranslator(model);
    }

    // ========================
    // 流式翻译器内部类
    // ========================

    /**
     * Responses SSE 事件 → Anthropic SSE 事件。
     * <p>
     * 状态机跟踪当前 block 类型、content_block_index，
     * 将 Responses 的 response.output_text.delta 等事件翻译为 Anthropic SSE 事件。
     */
    static class IRToAnthropicStreamTranslator implements StreamTranslator {

        private boolean done = false;
        private boolean messageStartSent = false;
        private String messageId;
        private String model;
        // 起始 -1：每个新块开启前都会先做 ++，首块发出的 index 就是 0（符合 Anthropic SSE 规范）。
        // Claude CLI 严格校验首块 index 必须为 0，否则会拒绝整个流。
        private int contentBlockIndex = -1;
        private boolean contentBlockOpen = false;
        private String currentBlockType = null; // "text", "thinking", "tool_use"
        private String currentToolName = null;
        private boolean currentToolHadDelta = false;
        private String currentToolArgs = "";
        private boolean hasToolCall = false;
        private final Map<Integer, Integer> outputIndexToBlockIdx = new HashMap<>();
        private final Set<Integer> textDeltasSeen = new HashSet<>();
        private final Set<Integer> refusalDeltasSeen = new HashSet<>();
        private final Set<Integer> toolArgumentDeltasSeen = new HashSet<>();
        private final Set<Integer> reasoningDeltasSeen = new HashSet<>();

        private int inputTokens = 0;
        private int outputTokens = 0;
        private int cacheReadInputTokens = 0;

        IRToAnthropicStreamTranslator(String model) {
            this.model = model != null ? model : "unknown";
            this.messageId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }

        @Override
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (done || line == null || line.isBlank()) return output;
            if (!line.startsWith("data: ")) return output;

            String json = line.substring(6);
            try {
                JsonNode root = JSON.readTree(json);
                String type = textOrDefault(root.get("type"), null);
                if (type == null) return output;

                switch (type) {
                    case "response.created" -> {
                        if (!messageStartSent) {
                            if (root.has("response")) {
                                JsonNode resp = root.get("response");
                                if (!isBlankText(resp.get("model"))) model = resp.get("model").asText();
                            }
                            appendEvent(output, "message_start",
                                    fmt("{\"type\":\"message_start\",\"message\":{\"id\":\"%s\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"%s\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}",
                                            messageId, escapeJsonValue(model)));
                            messageStartSent = true;
                        }
                    }
                    case "response.output_item.added" -> {
                        String itemType = root.has("item")
                                ? textOrDefault(root.get("item").get("type"), "")
                                : "";
                        int outputIndex = nonNegativeIntOrZero(root.get("output_index"));

                        closeCurrentBlock(output);

                        switch (itemType) {
                            case "message" -> { /* 不产出事件，由 text delta 隐式处理 */ }
                            case "reasoning" -> {
                                contentBlockIndex++;
                                outputIndexToBlockIdx.put(outputIndex, contentBlockIndex);
                                currentBlockType = "thinking";
                                contentBlockOpen = true;
                                appendEvent(output, "content_block_start",
                                        fmt("{\"type\":\"content_block_start\",\"index\":%d,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}",
                                                contentBlockIndex));
                            }
                            case "function_call" -> {
                                JsonNode item = root.get("item");
                                if (isBlankText(item.get("call_id")) || isBlankText(item.get("name"))) {
                                    log.debug("IR→Anthropic stream: function_call missing call_id or name, ignored");
                                    break;
                                }
                                contentBlockIndex++;
                                outputIndexToBlockIdx.put(outputIndex, contentBlockIndex);
                                currentBlockType = "tool_use";
                                contentBlockOpen = true;
                                currentToolName = item.get("name").asText();
                                String callId = fromResponsesCallID(item.get("call_id").asText());
                                currentToolHadDelta = false;
                                currentToolArgs = "";
                                hasToolCall = true;

                                appendEvent(output, "content_block_start",
                                        fmt("{\"type\":\"content_block_start\",\"index\":%d,\"content_block\":{\"type\":\"tool_use\",\"id\":\"%s\",\"name\":\"%s\",\"input\":{}}}",
                                                contentBlockIndex, escapeJsonValue(callId), escapeJsonValue(currentToolName)));
                            }
                            case "web_search_call" -> {
                                // 暂不处理
                                log.debug("IR→Anthropic stream: web_search_call not yet implemented");
                            }
                        }
                    }
                    case "response.output_text.delta" -> {
                        String text = textOrDefault(root.get("delta"), "");
                        if (!text.isEmpty()) {
                            textDeltasSeen.add(contentKey(root));
                            // 如果没有打开的 text block，自动开始一个
                            if (!contentBlockOpen || !"text".equals(currentBlockType)) {
                                closeCurrentBlock(output);
                                contentBlockIndex++;
                                currentBlockType = "text";
                                contentBlockOpen = true;
                                appendEvent(output, "content_block_start",
                                        fmt("{\"type\":\"content_block_start\",\"index\":%d,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                                                contentBlockIndex));
                            }
                            appendEvent(output, "content_block_delta",
                                    fmt("{\"type\":\"content_block_delta\",\"index\":%d,\"delta\":{\"type\":\"text_delta\",\"text\":\"%s\"}}",
                                            contentBlockIndex, escapeJsonValue(text)));
                        }
                    }
                    case "response.output_text.done" -> {
                        String text = textOrDefault(root.get("text"), "");
                        int key = contentKey(root);
                        if (!text.isEmpty() && !textDeltasSeen.contains(key)) {
                            if (!contentBlockOpen || !"text".equals(currentBlockType)) {
                                closeCurrentBlock(output);
                                contentBlockIndex++;
                                currentBlockType = "text";
                                contentBlockOpen = true;
                                appendEvent(output, "content_block_start",
                                        fmt("{\"type\":\"content_block_start\",\"index\":%d,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                                                contentBlockIndex));
                            }
                            appendEvent(output, "content_block_delta",
                                    fmt("{\"type\":\"content_block_delta\",\"index\":%d,\"delta\":{\"type\":\"text_delta\",\"text\":\"%s\"}}",
                                            contentBlockIndex, escapeJsonValue(text)));
                            textDeltasSeen.add(key);
                        }
                        closeCurrentBlock(output);
                    }
                    case "response.content_part.done" -> {
                        JsonNode part = root.path("part");
                        String partType = textOrDefault(part.get("type"), "");
                        int key = contentKey(root);
                        if ("output_text".equals(partType) && !textDeltasSeen.contains(key)) {
                            String text = textOrDefault(part.get("text"), "");
                            if (!text.isEmpty()) {
                                emitTextBlockDelta(output, text);
                                textDeltasSeen.add(key);
                            }
                        } else if ("refusal".equals(partType) && !refusalDeltasSeen.contains(key)) {
                            String refusal = textOrDefault(part.get("refusal"), "");
                            if (!refusal.isEmpty()) {
                                emitTextBlockDelta(output, refusal);
                                refusalDeltasSeen.add(key);
                            }
                        }
                        closeCurrentBlock(output);
                    }
                    case "response.refusal.delta" -> {
                        String delta = textOrDefault(root.get("delta"), "");
                        if (!delta.isEmpty()) {
                            refusalDeltasSeen.add(contentKey(root));
                            emitTextBlockDelta(output, delta);
                        }
                    }
                    case "response.refusal.done" -> {
                        String refusal = textOrDefault(root.get("refusal"), "");
                        int key = contentKey(root);
                        if (!refusal.isEmpty() && !refusalDeltasSeen.contains(key)) {
                            emitTextBlockDelta(output, refusal);
                            refusalDeltasSeen.add(key);
                        }
                        closeCurrentBlock(output);
                    }
                    case "response.function_call_arguments.delta" -> {
                        String delta = textOrDefault(root.get("delta"), "");
                        if (!delta.isEmpty()) {
                            int outputIdx = nonNegativeIntOrZero(root.get("output_index"));
                            if (!outputIndexToBlockIdx.containsKey(outputIdx)) {
                                log.debug("IR→Anthropic stream: function_call arguments delta without valid tool block ignored");
                                return output;
                            }
                            toolArgumentDeltasSeen.add(outputIdx);
                            if ("Read".equals(currentToolName)) {
                                // Read 工具：缓冲 delta，不实时输出
                                currentToolArgs += delta;
                            } else {
                                currentToolHadDelta = true;
                                int idx = findBlockIndex(root);
                                appendEvent(output, "content_block_delta",
                                        fmt("{\"type\":\"content_block_delta\",\"index\":%d,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"%s\"}}",
                                                idx, escapeJsonValue(delta)));
                            }
                        }
                    }
                    case "response.function_call_arguments.done" -> {
                        int outputIdx = nonNegativeIntOrZero(root.get("output_index"));
                        if (!outputIndexToBlockIdx.containsKey(outputIdx)) {
                            log.debug("IR→Anthropic stream: function_call arguments done without valid tool block ignored");
                            return output;
                        }
                        if (!currentToolHadDelta) {
                            String args = textOrDefault(root.get("arguments"), currentToolArgs);
                            if ("Read".equals(currentToolName)) {
                                args = sanitizeAnthropicToolUseInput(currentToolName, args);
                            }
                            if (!args.isEmpty() && !"{}".equals(args)) {
                                int idx = findBlockIndex(root);
                                appendEvent(output, "content_block_delta",
                                        fmt("{\"type\":\"content_block_delta\",\"index\":%d,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"%s\"}}",
                                                idx, escapeJsonValue(args)));
                                currentToolHadDelta = true;
                            }
                        }
                        closeCurrentBlock(output);
                    }
                    case "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                        String delta = textOrDefault(root.get("delta"), "");
                        if (!delta.isEmpty()) {
                            int outputIdx = nonNegativeIntOrZero(root.get("output_index"));
                            reasoningDeltasSeen.add(outputIdx);
                            int idx = findBlockIndex(root);
                            appendEvent(output, "content_block_delta",
                                    fmt("{\"type\":\"content_block_delta\",\"index\":%d,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"%s\"}}",
                                            idx, escapeJsonValue(delta)));
                        }
                    }
                    case "response.reasoning_summary_text.done", "response.reasoning_text.done" -> {
                        closeCurrentBlock(output);
                    }
                    case "response.output_item.done" -> {
                        String itemType = root.has("item") && root.get("item").has("type")
                                ? root.get("item").get("type").asText() : "";
                        if ("web_search_call".equals(itemType)
                                && "completed".equals(root.get("item").has("status")
                                    ? root.get("item").get("status").asText() : "")) {
                            // web_search_call completed → server_tool_use + web_search_tool_result
                            closeCurrentBlock(output);
                            contentBlockIndex++;
                            String srvId = "srvtoolu_" + (root.get("item").has("id")
                                    ? root.get("item").get("id").asText() : UUID.randomUUID());
                            String query = "";
                            JsonNode action = root.get("item").get("action");
                            if (action != null && action.isObject() && !isBlankText(action.get("query"))) {
                                query = action.get("query").asText();
                            }
                            appendEvent(output, "content_block_start",
                                    fmt("{\"type\":\"content_block_start\",\"index\":%d,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"%s\",\"name\":\"web_search\",\"input\":{\"query\":\"%s\"}}}",
                                            contentBlockIndex, escapeJsonValue(srvId), escapeJsonValue(query)));
                            appendEvent(output, "content_block_stop",
                                    fmt("{\"type\":\"content_block_stop\",\"index\":%d}", contentBlockIndex));
                            contentBlockIndex++;
                            appendEvent(output, "content_block_start",
                                    fmt("{\"type\":\"content_block_start\",\"index\":%d,\"content_block\":{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"%s\",\"content\":[]}}",
                                            contentBlockIndex, escapeJsonValue(srvId)));
                            appendEvent(output, "content_block_stop",
                                    fmt("{\"type\":\"content_block_stop\",\"index\":%d}", contentBlockIndex));
                        } else if (root.has("item")) {
                            int outputIndex = nonNegativeIntOrZero(root.get("output_index"));
                            emitFinalOutputItem(output, outputIndex, root.get("item"));
                        }
                    }
                    case "response.completed", "response.done", "response.incomplete", "response.failed" -> {
                        if (root.has("response") && root.get("response").has("output")
                                && root.get("response").get("output").isArray()) {
                            JsonNode responseOutput = root.get("response").get("output");
                            for (int i = 0; i < responseOutput.size(); i++) {
                                emitFinalOutputItem(output, i, responseOutput.get(i));
                            }
                        }
                        closeCurrentBlock(output);

                        // usage：优先从顶层 usage 读取，fallback 到 response.usage
                        int usageInput = 0, usageOutput = 0, usageCached = 0;
                        if (root.has("usage")) {
                            JsonNode u = root.get("usage");
                            if (isNonNegativeInt(u.get("input_tokens"))) usageInput = u.get("input_tokens").asInt();
                            if (isNonNegativeInt(u.get("output_tokens"))) usageOutput = u.get("output_tokens").asInt();
                            if (u.has("input_tokens_details")
                                    && isNonNegativeInt(u.get("input_tokens_details").get("cached_tokens"))) {
                                usageCached = u.get("input_tokens_details").get("cached_tokens").asInt();
                            }
                        } else if (root.has("response") && root.get("response").has("usage")) {
                            JsonNode u = root.get("response").get("usage");
                            if (isNonNegativeInt(u.get("input_tokens"))) usageInput = u.get("input_tokens").asInt();
                            if (isNonNegativeInt(u.get("output_tokens"))) usageOutput = u.get("output_tokens").asInt();
                            if (u.has("input_tokens_details")
                                    && isNonNegativeInt(u.get("input_tokens_details").get("cached_tokens"))) {
                                usageCached = u.get("input_tokens_details").get("cached_tokens").asInt();
                            }
                        }

                        inputTokens = Math.max(usageInput - usageCached, 0);
                        outputTokens = usageOutput;
                        cacheReadInputTokens = usageCached;

                        // stop_reason
                        String stopReason;
                        if (("response.incomplete".equals(type) || "response.done".equals(type))
                                && root.has("response")) {
                            JsonNode resp = root.get("response");
                            String respStatus = textOrDefault(resp.get("status"), "");
                            if ("incomplete".equals(respStatus)) {
                                String incompleteReason = textOrDefault(resp.path("incomplete_details").get("reason"), "");
                                stopReason = "max_output_tokens".equals(incompleteReason) ? "max_tokens" : "end_turn";
                            } else {
                                stopReason = hasToolCall ? "tool_use" : "end_turn";
                            }
                        } else {
                            stopReason = hasToolCall ? "tool_use" : "end_turn";
                        }

                        appendEvent(output, "message_delta",
                                fmt("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"%s\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":%d%s}}",
                                        stopReason, outputTokens,
                                        cacheReadInputTokens > 0
                                                ? fmt(",\"cache_read_input_tokens\":%d", cacheReadInputTokens)
                                                : ""));
                        appendEvent(output, "message_stop", "{\"type\":\"message_stop\"}");
                        done = true;
                    }
                }
            } catch (Exception e) {
                log.debug("IR→Anthropic SSE error: {}", e.getMessage());
            }
            return output;
        }

        private void emitFinalOutputItem(List<String> output, int outputIndex, JsonNode item) {
            String itemType = textOrDefault(item.get("type"), "");
            if ("message".equals(itemType) && item.has("content") && item.get("content").isArray()) {
                for (int contentIndex = 0; contentIndex < item.get("content").size(); contentIndex++) {
                    JsonNode part = item.get("content").get(contentIndex);
                    String partType = textOrDefault(part.get("type"), "");
                    int key = outputIndex * 10_000 + contentIndex;
                    if ("output_text".equals(partType) && !textDeltasSeen.contains(key)) {
                        String text = textOrDefault(part.get("text"), "");
                        if (!text.isEmpty()) {
                            emitTextBlockDelta(output, text);
                            textDeltasSeen.add(key);
                        }
                    } else if ("refusal".equals(partType) && !refusalDeltasSeen.contains(key)) {
                        String refusal = textOrDefault(part.get("refusal"), "");
                        if (!refusal.isEmpty()) {
                            emitTextBlockDelta(output, refusal);
                            refusalDeltasSeen.add(key);
                        }
                    }
                }
            } else if ("function_call".equals(itemType)) {
                if (isBlankText(item.get("call_id")) || isBlankText(item.get("name"))) {
                    log.debug("IR→Anthropic stream: final function_call missing call_id or name, ignored");
                    return;
                }
                hasToolCall = true;
                Integer blockIndex = outputIndexToBlockIdx.get(outputIndex);
                if (blockIndex == null) {
                    closeCurrentBlock(output);
                    contentBlockIndex++;
                    blockIndex = contentBlockIndex;
                    outputIndexToBlockIdx.put(outputIndex, blockIndex);
                    currentBlockType = "tool_use";
                    contentBlockOpen = true;
                    currentToolName = item.get("name").asText();
                    String callId = fromResponsesCallID(item.get("call_id").asText());
                    appendEvent(output, "content_block_start",
                            fmt("{\"type\":\"content_block_start\",\"index\":%d,\"content_block\":{\"type\":\"tool_use\",\"id\":\"%s\",\"name\":\"%s\",\"input\":{}}}",
                                    blockIndex, escapeJsonValue(callId), escapeJsonValue(currentToolName)));
                }
                String arguments = textOrDefault(item.get("arguments"), "");
                if (!arguments.isEmpty() && !toolArgumentDeltasSeen.contains(outputIndex)) {
                    if (currentToolName != null) {
                        arguments = sanitizeAnthropicToolUseInput(currentToolName, arguments);
                    }
                    if (!arguments.isEmpty() && !"{}".equals(arguments)) {
                        appendEvent(output, "content_block_delta",
                                fmt("{\"type\":\"content_block_delta\",\"index\":%d,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"%s\"}}",
                                        blockIndex, escapeJsonValue(arguments)));
                    }
                    toolArgumentDeltasSeen.add(outputIndex);
                }
                closeCurrentBlock(output);
            } else if ("reasoning".equals(itemType) && !reasoningDeltasSeen.contains(outputIndex)
                    && (item.has("content") || item.has("summary"))) {
                String thinking = extractReasoningText(item);
                if (!thinking.isEmpty()) {
                    closeCurrentBlock(output);
                    contentBlockIndex++;
                    outputIndexToBlockIdx.put(outputIndex, contentBlockIndex);
                    currentBlockType = "thinking";
                    contentBlockOpen = true;
                    appendEvent(output, "content_block_start",
                            fmt("{\"type\":\"content_block_start\",\"index\":%d,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}",
                                    contentBlockIndex));
                    appendEvent(output, "content_block_delta",
                            fmt("{\"type\":\"content_block_delta\",\"index\":%d,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"%s\"}}",
                                    contentBlockIndex, escapeJsonValue(thinking)));
                    reasoningDeltasSeen.add(outputIndex);
                    closeCurrentBlock(output);
                }
            }
        }

        private void closeCurrentBlock(List<String> output) {
            if (contentBlockOpen) {
                appendEvent(output, "content_block_stop",
                        fmt("{\"type\":\"content_block_stop\",\"index\":%d}", contentBlockIndex));
                contentBlockOpen = false;
                currentBlockType = null;
            }
        }

        private int findBlockIndex(JsonNode root) {
            if (isNonNegativeInt(root.get("output_index"))) {
                int oi = root.get("output_index").asInt();
                return outputIndexToBlockIdx.getOrDefault(oi, contentBlockIndex);
            }
            return contentBlockIndex;
        }

        private void emitTextBlockDelta(List<String> output, String text) {
            if (!contentBlockOpen || !"text".equals(currentBlockType)) {
                closeCurrentBlock(output);
                contentBlockIndex++;
                currentBlockType = "text";
                contentBlockOpen = true;
                appendEvent(output, "content_block_start",
                        fmt("{\"type\":\"content_block_start\",\"index\":%d,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                                contentBlockIndex));
            }
            appendEvent(output, "content_block_delta",
                    fmt("{\"type\":\"content_block_delta\",\"index\":%d,\"delta\":{\"type\":\"text_delta\",\"text\":\"%s\"}}",
                            contentBlockIndex, escapeJsonValue(text)));
        }

        private int contentKey(JsonNode root) {
            int outputIdx = nonNegativeIntOrZero(root.get("output_index"));
            int contentIdx = nonNegativeIntOrZero(root.get("content_index"));
            return outputIdx * 10_000 + contentIdx;
        }

        @Override public boolean isDone() { return done; }
        @Override public int getInputTokens() { return inputTokens; }
        @Override public int getOutputTokens() { return outputTokens; }
    }

    // ========================
    // 辅助方法：requestFromIR
    // ========================

    /**
     * function_call input item → assistant message with tool_use block。
     */
    private static JsonNode convertFunctionCallToAssistantMsg(JsonNode item) {
        if (isBlankText(item.get("call_id")) || isBlankText(item.get("name"))) {
            log.debug("Responses→Anthropic request: function_call missing call_id or name, ignored");
            return null;
        }
        ObjectNode msg = JSON.createObjectNode();
        msg.put("role", "assistant");
        ArrayNode content = JSON.createArrayNode();
        ObjectNode toolUse = JSON.createObjectNode();
        toolUse.put("type", "tool_use");
        toolUse.put("id", fromResponsesCallIDToAnthropic(item.get("call_id").asText()));
        toolUse.put("name", item.get("name").asText());
        String args = item.has("arguments") ? item.get("arguments").asText() : "{}";
        args = sanitizeAnthropicToolUseInput(item.get("name").asText(), args);
        try {
            toolUse.set("input", JSON.readTree(args));
        } catch (Exception e) {
            toolUse.put("input", args);
        }
        content.add(toolUse);
        msg.set("content", content);
        return msg;
    }

    private static JsonNode convertReasoningToAssistantMsg(JsonNode item) {
        ObjectNode msg = JSON.createObjectNode();
        msg.put("role", "assistant");
        ArrayNode content = JSON.createArrayNode();
        addReasoningBlocks(content, item);
        msg.set("content", content);
        return msg;
    }

    private static String appendSystemText(String current, String next) {
        if (next == null || next.isEmpty()) return current;
        return current == null || current.isEmpty() ? next : current + "\n\n" + next;
    }

    /**
     * function_call_output input item → user message with tool_result block。
     */
    private static JsonNode convertFunctionCallOutputToUserMsg(JsonNode item) {
        if (isBlankText(item.get("call_id"))) {
            log.debug("Responses→Anthropic request: function_call_output missing call_id, ignored");
            return null;
        }
        ObjectNode msg = JSON.createObjectNode();
        msg.put("role", "user");
        ArrayNode content = JSON.createArrayNode();
        ObjectNode toolResult = JSON.createObjectNode();
        toolResult.put("type", "tool_result");
        toolResult.put("tool_use_id", fromResponsesCallIDToAnthropic(item.get("call_id").asText()));
        // output 字段 → tool_result content
        String output = textOrDefault(item.get("output"), "");
        ArrayNode trContent = JSON.createArrayNode();
        ObjectNode trText = JSON.createObjectNode();
        trText.put("type", "text");
        trText.put("text", output);
        trContent.add(trText);
        toolResult.set("content", trContent);
        content.add(toolResult);
        msg.set("content", content);
        return msg;
    }

    /**
     * Responses user content → Anthropic content（字符串或数组）。
     */
    private static JsonNode convertUserContentToAnthropic(JsonNode content) {
        if (content == null) return null;
        if (content.isTextual()) return content;

        if (content.isArray()) {
            ArrayNode blocks = JSON.createArrayNode();
            for (JsonNode part : content) {
                String partType = textOrDefault(part.get("type"), "");
                switch (partType) {
                    case "input_text", "text" -> {
                        if (isBlankText(part.get("text"))) {
                            break;
                        }
                        ObjectNode textBlock = JSON.createObjectNode();
                        textBlock.put("type", "text");
                        textBlock.put("text", part.get("text").asText());
                        blocks.add(textBlock);
                    }
                    case "input_image" -> {
                        String imageUrl = textOrDefault(part.get("image_url"), "");
                        ObjectNode source = dataURIToAnthropicImageSource(imageUrl);
                        if (source != null) {
                            ObjectNode imageBlock = JSON.createObjectNode();
                            imageBlock.put("type", "image");
                            imageBlock.set("source", source);
                            blocks.add(imageBlock);
                        }
                    }
                    case "input_file" -> {
                        ObjectNode documentBlock = responsesInputFileToAnthropicDocument(part);
                        if (documentBlock != null) {
                            blocks.add(documentBlock);
                        }
                    }
                    case "tool_result" -> {
                        JsonNode toolResult = sanitizeAnthropicToolResultBlock(part);
                        if (toolResult != null) {
                            blocks.add(toolResult);
                        }
                    }
                    default ->
                        log.debug("Responses→Anthropic: unknown user content part type '{}'", partType);
                }
            }
            if (blocks.size() == 0) return null;
            return blocks;
        }
        return null;
    }

    /**
     * Responses input_file content part → Anthropic document content block.
     * The conversion is limited to file_url, file_data, and file_id because those
     * are the stable file payload shapes both protocols can represent.
     */
    private static ObjectNode responsesInputFileToAnthropicDocument(JsonNode part) {
        if (part == null || !part.isObject()) return null;

        ObjectNode source = JSON.createObjectNode();
        if (!isBlankText(part.get("file_url"))) {
            source.put("type", "url");
            source.put("url", part.get("file_url").asText());
        } else if (!isBlankText(part.get("file_data"))) {
            String fileData = part.get("file_data").asText();
            String mediaType = "application/pdf";
            String data = fileData;
            if (fileData.startsWith("data:")) {
                int colonIdx = fileData.indexOf(':');
                int semicolonIdx = fileData.indexOf(';');
                int commaIdx = fileData.indexOf(',');
                if (colonIdx >= 0 && semicolonIdx > colonIdx && commaIdx > semicolonIdx) {
                    mediaType = fileData.substring(colonIdx + 1, semicolonIdx);
                    data = fileData.substring(commaIdx + 1);
                }
            }
            if (data.isBlank()) return null;
            source.put("type", "base64");
            source.put("media_type", mediaType);
            source.put("data", data);
        } else if (!isBlankText(part.get("file_id"))) {
            source.put("type", "file");
            source.put("file_id", part.get("file_id").asText());
        } else {
            return null;
        }

        ObjectNode documentBlock = JSON.createObjectNode();
        documentBlock.put("type", "document");
        if (!isBlankText(part.get("filename"))) {
            documentBlock.put("title", part.get("filename").asText());
        }
        documentBlock.set("source", source);
        return documentBlock;
    }

    /**
     * Responses assistant content → Anthropic content。
     */
    private static JsonNode convertAssistantContentToAnthropic(JsonNode content) {
        if (content == null) return emptyAnthropicTextBlocks();
        if (content.isTextual()) {
            ArrayNode blocks = JSON.createArrayNode();
            ObjectNode textBlock = JSON.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", content.asText());
            blocks.add(textBlock);
            return blocks;
        }
        if (content.isArray()) {
            ArrayNode blocks = JSON.createArrayNode();
            for (JsonNode part : content) {
                String partType = textOrDefault(part.get("type"), "");
                if ("output_text".equals(partType) || "text".equals(partType)) {
                    String text = textOrDefault(part.get("text"), "");
                    if (text.isEmpty()) {
                        continue;
                    }
                    ObjectNode textBlock = JSON.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", text);
                    blocks.add(textBlock);
                } else if ("thinking".equals(partType)) {
                    JsonNode thinking = sanitizeAnthropicThinkingBlock(part);
                    if (thinking != null) {
                        blocks.add(thinking);
                    }
                } else if ("redacted_thinking".equals(partType)) {
                    JsonNode redacted = sanitizeAnthropicRedactedThinkingBlock(part);
                    if (redacted != null) {
                        blocks.add(redacted);
                    }
                } else if ("tool_use".equals(partType)) {
                    JsonNode toolUse = sanitizeAnthropicToolUseBlock(part);
                    if (toolUse != null) {
                        blocks.add(toolUse);
                    }
                }
            }
            if (blocks.size() == 0) return emptyAnthropicTextBlocks();
            return blocks;
        }
        return null;
    }

    private static ArrayNode emptyAnthropicTextBlocks() {
        ArrayNode blocks = JSON.createArrayNode();
        ObjectNode textBlock = JSON.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", "");
        blocks.add(textBlock);
        return blocks;
    }

    private static JsonNode sanitizeAnthropicToolResultBlock(JsonNode part) {
        if (part == null || !part.isObject() || isBlankText(part.get("tool_use_id"))) return null;
        ObjectNode toolResult = JSON.createObjectNode();
        toolResult.put("type", "tool_result");
        toolResult.put("tool_use_id", part.get("tool_use_id").asText());
        JsonNode content = part.get("content");
        if (content == null || content.isNull()) {
            toolResult.set("content", JSON.createArrayNode());
        } else if (content.isTextual()) {
            toolResult.put("content", content.asText());
        } else if (content.isArray()) {
            ArrayNode blocks = JSON.createArrayNode();
            for (JsonNode block : content) {
                String type = textOrDefault(block.get("type"), "");
                if ("text".equals(type) && block.has("text") && block.get("text").isTextual()) {
                    ObjectNode textBlock = JSON.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", block.get("text").asText());
                    blocks.add(textBlock);
                }
            }
            toolResult.set("content", blocks);
        } else {
            toolResult.set("content", JSON.createArrayNode());
        }
        return toolResult;
    }

    private static JsonNode sanitizeAnthropicThinkingBlock(JsonNode part) {
        String thinking = textOrDefault(part.get("thinking"), "");
        if (thinking.isEmpty()) return null;
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "thinking");
        block.put("thinking", thinking);
        copyTextIfExists(part, block, "signature");
        return block;
    }

    private static JsonNode sanitizeAnthropicRedactedThinkingBlock(JsonNode part) {
        String data = textOrDefault(part.get("data"), "");
        if (data.isEmpty()) return null;
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "redacted_thinking");
        block.put("data", data);
        return block;
    }

    private static JsonNode sanitizeAnthropicToolUseBlock(JsonNode part) {
        if (part == null || !part.isObject() || isBlankText(part.get("id")) || isBlankText(part.get("name"))) {
            return null;
        }
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "tool_use");
        block.put("id", part.get("id").asText());
        block.put("name", part.get("name").asText());
        block.set("input", part.has("input") && part.get("input").isObject()
                ? part.get("input")
                : JSON.createObjectNode());
        return block;
    }

    /**
     * data URI → Anthropic image source。
     */
    private static ObjectNode dataURIToAnthropicImageSource(String dataUri) {
        if (dataUri == null || dataUri.isBlank()) return null;
        if (dataUri.startsWith("http://") || dataUri.startsWith("https://")) {
            ObjectNode source = JSON.createObjectNode();
            source.put("type", "url");
            source.put("url", dataUri);
            return source;
        }
        if (!dataUri.startsWith("data:")) return null;
        try {
            int colonIdx = dataUri.indexOf(':');
            int semicolonIdx = dataUri.indexOf(';');
            int commaIdx = dataUri.indexOf(',');
            if (colonIdx < 0 || commaIdx < 0) return null;

            String mediaType = dataUri.substring(colonIdx + 1, semicolonIdx > 0 ? semicolonIdx : commaIdx);
            String data = dataUri.substring(commaIdx + 1);
            if (data.isEmpty()) return null;

            ObjectNode source = JSON.createObjectNode();
            source.put("type", "base64");
            source.put("media_type", mediaType);
            source.put("data", data);
            return source;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 合并相邻同角色消息（Anthropic 要求 user/assistant 交替）。
     */
    private static ArrayNode mergeConsecutiveMessages(ArrayNode messages) {
        if (messages.size() <= 1) return messages;

        ArrayNode merged = JSON.createArrayNode();
        JsonNode prev = null;
        String prevRole = null;

        for (JsonNode msg : messages) {
            String role = msg.has("role") ? msg.get("role").asText() : "user";
            if (prev != null && role.equals(prevRole)) {
                // 合并 content
                prev = mergeMessageContent(prev, msg);
            } else {
                if (prev != null) merged.add(prev);
                prev = msg.deepCopy();
                prevRole = role;
            }
        }
        if (prev != null) merged.add(prev);
        return merged;
    }

    private static JsonNode mergeMessageContent(JsonNode prev, JsonNode next) {
        JsonNode prevContent = prev.get("content");
        JsonNode nextContent = next.get("content");

        ArrayNode mergedContent = JSON.createArrayNode();
        addAllContentBlocks(mergedContent, prevContent);
        addAllContentBlocks(mergedContent, nextContent);

        ObjectNode result = prev.deepCopy();
        result.set("content", mergedContent);
        return result;
    }

    private static void addAllContentBlocks(ArrayNode target, JsonNode content) {
        if (content == null) return;
        if (content.isArray()) {
            for (JsonNode block : content) {
                target.add(block);
            }
        } else if (content.isTextual()) {
            ObjectNode textBlock = JSON.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", content.asText());
            target.add(textBlock);
        }
    }

    /**
     * 转换 Responses tool 到 Anthropic tool。
     */
    private static JsonNode convertResponsesToolToAnthropic(JsonNode tool) {
        String toolType = tool.has("type") ? tool.get("type").asText() : "";
        // web_search 系列 → Anthropic web_search tool
        if ("web_search".equals(toolType) || "google_search".equals(toolType)
                || "web_search_preview".equals(toolType)
                || "web_search_20250305".equals(toolType)) {
            ObjectNode ws = JSON.createObjectNode();
            ws.put("type", "web_search_20250305");
            ws.put("name", "web_search");
            ObjectNode userLocation = normalizeWebSearchUserLocation(tool.get("user_location"));
            if (userLocation != null) {
                ws.set("user_location", userLocation);
            }
            return ws;
        }
        // function tool → Anthropic tool
        if ("function".equals(toolType)) {
            if (isBlankText(tool.get("name"))) return null;
            ObjectNode func = JSON.createObjectNode();
            func.put("name", tool.get("name").asText());
            copyTextIfExists(tool, func, "description");
            JsonNode params = tool.has("parameters") ? tool.get("parameters") : null;
            func.set("input_schema", normalizeInputSchema(params));
            return func;
        }
        return null;
    }

    private static JsonNode normalizeInputSchema(JsonNode schema) {
        if (schema == null || schema.isNull() || (schema.isTextual() && "null".equals(schema.asText()))) {
            ObjectNode empty = JSON.createObjectNode();
            empty.put("type", "object");
            empty.set("properties", JSON.createObjectNode());
            return empty;
        }
        if (!schema.isObject()) {
            ObjectNode empty = JSON.createObjectNode();
            empty.put("type", "object");
            empty.set("properties", JSON.createObjectNode());
            return empty;
        }
        return schema;
    }

    /**
     * 转换 Responses tool_choice 到 Anthropic tool_choice。
     */
    private static JsonNode convertResponsesToolChoiceToAnthropic(JsonNode toolChoice) {
        if (toolChoice.isTextual()) {
            String tc = toolChoice.asText();
            return switch (tc) {
                case "auto" -> {
                    ObjectNode obj = JSON.createObjectNode();
                    obj.put("type", "auto");
                    yield obj;
                }
                case "required" -> {
                    ObjectNode obj = JSON.createObjectNode();
                    obj.put("type", "any");
                    yield obj;
                }
                case "none" -> {
                    ObjectNode obj = JSON.createObjectNode();
                    obj.put("type", "none");
                    yield obj;
                }
                default -> null;
            };
        }
        if (toolChoice.isObject() && "function".equals(toolChoice.has("type") ? toolChoice.get("type").asText() : "")) {
            JsonNode nameNode = toolChoice.has("function") && toolChoice.get("function").isObject()
                    ? toolChoice.get("function").get("name")
                    : toolChoice.get("name");
            if (isBlankText(nameNode)) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put("type", "tool");
            obj.put("name", nameNode.asText());
            return obj;
        }
        if (toolChoice.isObject()) {
            String type = toolChoice.path("type").asText("");
            if ("web_search".equals(type) || "web_search_preview".equals(type)
                    || "google_search".equals(type) || "web_search_20250305".equals(type)) {
                ObjectNode obj = JSON.createObjectNode();
                obj.put("type", "tool");
                obj.put("name", "web_search");
                return obj;
            }
        }
        return null;
    }

    /**
     * Responses effort → Anthropic effort。
     * minimal→low，low/medium/high 保持同名，xhigh→max；none/未知值不透传。
     */
    private static String mapResponsesEffortToAnthropic(JsonNode effort) {
        if (effort == null || !effort.isTextual()) return null;
        return switch (effort.asText()) {
            case "minimal", "low" -> "low";
            case "medium" -> "medium";
            case "high" -> "high";
            case "xhigh" -> "max";
            default -> null;
        };
    }

    private static ObjectNode normalizeWebSearchUserLocation(JsonNode userLocation) {
        if (userLocation == null || !userLocation.isObject()) return null;
        ObjectNode normalized = JSON.createObjectNode();
        copyTextIfExists(userLocation, normalized, "type");
        copyTextIfExists(userLocation, normalized, "country");
        copyTextIfExists(userLocation, normalized, "region");
        copyTextIfExists(userLocation, normalized, "city");
        copyTextIfExists(userLocation, normalized, "timezone");
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 默认 thinking budget_tokens。
     */
    private static int defaultThinkingBudget(String effort) {
        return switch (effort) {
            case "low" -> 1024;
            case "medium" -> 4096;
            case "high" -> 10240;
            case "max" -> 32768;
            default -> 10240;
        };
    }

    // ========================
    // 辅助方法：responseFromIR
    // ========================

    /**
     * Responses status → Anthropic stop_reason。
     */
    private static String mapResponsesStatusToAnthropicStopReason(String status, JsonNode ir, boolean hasToolUse) {
        if ("incomplete".equals(status)) {
            if ("max_output_tokens".equals(textOrDefault(ir.path("incomplete_details").get("reason"), ""))) {
                return "max_tokens";
            }
            return "end_turn";
        }
        if ("completed".equals(status)) {
            return hasToolUse ? "tool_use" : "end_turn";
        }
        return "end_turn";
    }

    private static String extractReasoningText(JsonNode item) {
        StringBuilder text = new StringBuilder();
        if (item.has("content") && item.get("content").isArray()) {
            for (JsonNode part : item.get("content")) {
                String type = textOrDefault(part.get("type"), "");
                String partText = textOrDefault(part.get("text"), "");
                if (("reasoning_text".equals(type) || "text".equals(type)) && !partText.isEmpty()) {
                    if (text.length() > 0) text.append("\n");
                    text.append(partText);
                }
            }
        }
        if (text.length() > 0) return text.toString();

        return extractReasoningSummaryText(item);
    }

    private static String extractReasoningSummaryText(JsonNode item) {
        StringBuilder text = new StringBuilder();
        if (item.has("summary") && item.get("summary").isArray()) {
            for (JsonNode summary : item.get("summary")) {
                String summaryText = textOrDefault(summary.get("text"), "");
                if (!"summary_text".equals(textOrDefault(summary.get("type"), "")) || summaryText.isEmpty()) continue;
                text.append(summaryText);
            }
        }
        return text.toString();
    }

    private static void addResponseReasoningBlocks(ArrayNode content, JsonNode item) {
        String text = extractReasoningSummaryText(item);
        if (!text.isEmpty()) {
            ObjectNode thinkingBlock = JSON.createObjectNode();
            thinkingBlock.put("type", "thinking");
            thinkingBlock.put("thinking", text);
            content.add(thinkingBlock);
        }
    }

    private static void addReasoningBlocks(ArrayNode content, JsonNode item) {
        String text = extractReasoningText(item);
        String encryptedContent = textOrDefault(item.get("encrypted_content"), "");

        if (!text.isEmpty() || item.has("content") || item.has("summary")) {
            ObjectNode thinkingBlock = JSON.createObjectNode();
            thinkingBlock.put("type", "thinking");
            thinkingBlock.put("thinking", text);
            if (!encryptedContent.isEmpty()) {
                thinkingBlock.put("signature", encryptedContent);
            }
            content.add(thinkingBlock);
            return;
        }

        if (!encryptedContent.isEmpty()) {
            ObjectNode redactedBlock = JSON.createObjectNode();
            redactedBlock.put("type", "redacted_thinking");
            redactedBlock.put("data", encryptedContent);
            content.add(redactedBlock);
        }
    }

    private static void addWebSearchBlocks(ArrayNode content, JsonNode item) {
        String sourceId = textOrDefault(item.get("id"), null);
        if (sourceId == null) {
            sourceId = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }
        String toolUseId = "srvtoolu_" + sourceId;

        ObjectNode serverToolUse = JSON.createObjectNode();
        serverToolUse.put("type", "server_tool_use");
        serverToolUse.put("id", toolUseId);
        serverToolUse.put("name", "web_search");
        ObjectNode input = JSON.createObjectNode();
        JsonNode action = item.get("action");
        String query = "";
        if (action != null && action.isObject() && !isBlankText(action.get("query"))) {
            query = action.get("query").asText();
        }
        input.put("query", query);
        serverToolUse.set("input", input);
        content.add(serverToolUse);

        ObjectNode toolResult = JSON.createObjectNode();
        toolResult.put("type", "web_search_tool_result");
        toolResult.put("tool_use_id", toolUseId);
        toolResult.set("content", JSON.createArrayNode());
        content.add(toolResult);
    }

    /**
     * Read 工具空 pages 字段清理。
     * 仅当 tool name 为 "Read" 且 pages 为 JSON 空字符串时删除该字段。
     */
    private static String sanitizeAnthropicToolUseInput(String toolName, String arguments) {
        if (!"Read".equals(toolName)) return arguments;
        try {
            JsonNode args = JSON.readTree(arguments);
            if (args.isObject() && args.has("pages")) {
                JsonNode pages = args.get("pages");
                if (pages.isTextual() && pages.asText().isEmpty()) {
                    ((ObjectNode) args).remove("pages");
                    return args.toString();
                }
            }
        } catch (Exception e) {
            // 非 JSON，保持原样
        }
        return arguments;
    }

    // ========================
    // call_id 映射
    // ========================

    /**
     * fromResponsesCallID（响应路径）：
     * 剥离 "fc_" 前缀，未知 ID 保持原样（不加前缀）。
     */
    private static String fromResponsesCallID(String callId) {
        if (callId == null) return "";
        if (callId.startsWith("fc_")) {
            String remainder = callId.substring(3);
            if (remainder.startsWith("toolu_") || remainder.startsWith("call_")) {
                return remainder;
            }
        }
        return callId;
    }

    /**
     * fromResponsesCallIDToAnthropic（请求路径）：
     * 剥离 "fc_" 前缀，不以 "toolu_"/"call_" 开头时自动加 "toolu_" 前缀。
     */
    private static String fromResponsesCallIDToAnthropic(String callId) {
        if (callId == null) return "toolu_" + UUID.randomUUID();
        if (callId.startsWith("fc_")) {
            String remainder = callId.substring(3);
            if (remainder.startsWith("toolu_") || remainder.startsWith("call_")) {
                return remainder;
            }
        }
        if (callId.startsWith("toolu_") || callId.startsWith("call_")) {
            return callId;
        }
        return "toolu_" + callId;
    }

    /**
     * 从 content 提取纯文本（支持字符串和数组格式）。
     */
    private static String extractTextFromContent(JsonNode content) {
        if (content == null) return null;
        if (content.isTextual()) return content.asText().isBlank() ? null : content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String partType = part.has("type") ? part.get("type").asText() : "";
                if ("input_text".equals(partType) || "output_text".equals(partType) || "text".equals(partType)) {
                    if (!isBlankText(part.get("text"))) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(part.get("text").asText());
                    }
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        return null;
    }

    // ========================
    // 通用工具方法
    // ========================

    private static void copyIfExists(JsonNode src, ObjectNode dst, String field) {
        if (src.has(field) && !src.get(field).isNull()) dst.set(field, src.get(field));
    }

    private static void copyObjectIfExists(JsonNode src, ObjectNode dst, String field) {
        if (src.has(field) && src.get(field).isObject()) dst.set(field, src.get(field));
    }

    private static void copyTextIfExists(JsonNode src, ObjectNode dst, String field) {
        if (src.has(field) && src.get(field).isTextual() && !src.get(field).asText().isBlank()) {
            dst.set(field, src.get(field));
        }
    }

    private static void copyBooleanIfExists(JsonNode src, ObjectNode dst, String field) {
        if (src.has(field) && src.get(field).isBoolean()) dst.set(field, src.get(field));
    }

    private static void copyNumberIfExists(JsonNode src, ObjectNode dst, String field) {
        if (src.has(field) && src.get(field).isNumber()) dst.set(field, src.get(field));
    }

    private static boolean isBlankText(JsonNode node) {
        return node == null || !node.isTextual() || node.asText().isBlank();
    }

    private static String textOrDefault(JsonNode node, String defaultValue) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : defaultValue;
    }

    private static boolean isPositiveInt(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.asInt() > 0;
    }

    private static boolean isNonNegativeInt(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.asInt() >= 0;
    }

    private static int nonNegativeIntOrZero(JsonNode node) {
        return isNonNegativeInt(node) ? node.asInt() : 0;
    }

    private static JsonNode normalizeStopSequences(JsonNode stop) {
        if (stop == null || !stop.isArray()) return null;
        ArrayNode arr = JSON.createArrayNode();
        for (JsonNode item : stop) {
            if (item.isTextual() && !item.asText().isBlank()) {
                arr.add(item.asText());
            }
        }
        return arr.isEmpty() ? null : arr;
    }

    private static void appendEvent(List<String> output, String event, String data) {
        output.add("event: " + event);
        output.add("data: " + data);
        output.add("");
    }

    private static String escapeJsonValue(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String fmt(String format, Object... args) {
        return String.format(format, args);
    }
}
