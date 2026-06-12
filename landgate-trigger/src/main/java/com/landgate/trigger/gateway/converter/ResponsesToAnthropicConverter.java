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

            copyIfExists(ir, dst, "model");
            copyIfExists(ir, dst, "stream");
            copyTextIfExists(ir, dst, "service_tier");
            copyIfExists(ir, dst, "temperature");
            copyIfExists(ir, dst, "top_p");
            copyObjectIfExists(ir, dst, "metadata");

            // max_output_tokens → max_tokens（无值时默认 8192）
            if (ir.has("max_output_tokens")) {
                int maxTokens = ir.get("max_output_tokens").asInt();
                dst.put("max_tokens", maxTokens > 0 ? maxTokens : 8192);
            } else {
                dst.put("max_tokens", 8192);
            }

            // 内部 IR stop 扩展 → Anthropic stop_sequences
            if (ir.has("_landgate_stop_sequences") && ir.get("_landgate_stop_sequences").isArray()
                    && ir.get("_landgate_stop_sequences").size() > 0) {
                dst.set("stop_sequences", ir.get("_landgate_stop_sequences"));
            }

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
                        String role = item.has("role") ? item.get("role").asText() : null;
                        String itemType = item.has("type") ? item.get("type").asText() : null;

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
                String role = item.has("role") ? item.get("role").asText() : null;
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
            boolean disableParallelToolUse = ir.has("parallel_tool_calls")
                    && !ir.get("parallel_tool_calls").asBoolean(true);
            if (ir.has("tool_choice")) {
                JsonNode toolChoice = convertResponsesToolChoiceToAnthropic(ir.get("tool_choice"));
                if (toolChoice instanceof ObjectNode objectNode && disableParallelToolUse) {
                    objectNode.put("disable_parallel_tool_use", true);
                }
                if (toolChoice != null) {
                    dst.set("tool_choice", toolChoice);
                } else if (disableParallelToolUse) {
                    ObjectNode fallbackToolChoice = JSON.createObjectNode();
                    fallbackToolChoice.put("type", "auto");
                    fallbackToolChoice.put("disable_parallel_tool_use", true);
                    dst.set("tool_choice", fallbackToolChoice);
                }
            } else if (disableParallelToolUse) {
                ObjectNode toolChoice = JSON.createObjectNode();
                toolChoice.put("type", "auto");
                toolChoice.put("disable_parallel_tool_use", true);
                dst.set("tool_choice", toolChoice);
            }

            // --- reasoning.effort → output_config.effort + thinking ---
            if (ir.has("reasoning") && ir.get("reasoning").has("effort")) {
                String effort = ir.get("reasoning").get("effort").asText();
                String anthropicEffort = mapResponsesEffortToAnthropic(effort);
                ObjectNode outputConfig = JSON.createObjectNode();
                outputConfig.put("effort", anthropicEffort);
                dst.set("output_config", outputConfig);

                if (!"low".equals(anthropicEffort)) {
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

            dst.put("id", ir.has("id") ? ir.get("id").asText()
                    : "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            dst.put("type", "message");
            dst.put("role", "assistant");
            dst.put("model", ir.has("model") ? ir.get("model").asText() : "unknown");

            // output[] → content[]
            ArrayNode content = JSON.createArrayNode();
            boolean hasToolUse = false;

            if (ir.has("output") && ir.get("output").isArray()) {
                for (JsonNode item : ir.get("output")) {
                    String itemType = item.has("type") ? item.get("type").asText() : "";

                    switch (itemType) {
                        case "reasoning" -> {
                            addReasoningBlocks(content, item);
                        }
                        case "message" -> {
                            JsonNode msgContent = item.get("content");
                            if (msgContent != null && msgContent.isArray()) {
                                for (JsonNode part : msgContent) {
                                    String partType = part.has("type") ? part.get("type").asText() : "";
                                    if ("output_text".equals(partType)) {
                                        ObjectNode textBlock = JSON.createObjectNode();
                                        textBlock.put("type", "text");
                                        textBlock.put("text", part.has("text") ? part.get("text").asText() : "");
                                        content.add(textBlock);
                                    } else if ("refusal".equals(partType)) {
                                        ObjectNode textBlock = JSON.createObjectNode();
                                        textBlock.put("type", "text");
                                        textBlock.put("text", part.has("refusal") ? part.get("refusal").asText() : "");
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
                            String args = item.has("arguments") ? item.get("arguments").asText() : "{}";
                            args = sanitizeAnthropicToolUseInput(item.get("name").asText(), args);
                            try {
                                toolUse.set("input", JSON.readTree(args));
                            } catch (Exception e) {
                                toolUse.put("input", args);
                            }
                            content.add(toolUse);
                        }
                        case "web_search_call" -> {
                            // 非流式路径不处理 web_search_call（与 Sub2API 一致）
                            log.debug("Responses→Anthropic: web_search_call ignored in non-streaming");
                        }
                        default ->
                            log.debug("Responses→Anthropic: unknown output type '{}'", itemType);
                    }
                }
            }

            dst.set("content", content);

            // stop_reason
            String status = ir.has("status") ? ir.get("status").asText() : "completed";
            String stopReason = mapResponsesStatusToAnthropicStopReason(status, ir, hasToolUse);
            dst.put("stop_reason", stopReason);

            // usage
            if (ir.has("usage")) {
                JsonNode usage = ir.get("usage");
                int inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
                int outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
                int cachedTokens = 0;
                if (usage.has("input_tokens_details") && usage.get("input_tokens_details").has("cached_tokens")) {
                    cachedTokens = usage.get("input_tokens_details").get("cached_tokens").asInt();
                }

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
                String type = root.has("type") ? root.get("type").asText() : null;
                if (type == null) return output;

                switch (type) {
                    case "response.created" -> {
                        if (!messageStartSent) {
                            if (root.has("response")) {
                                JsonNode resp = root.get("response");
                                if (resp.has("model")) model = resp.get("model").asText();
                            }
                            appendEvent(output, "message_start",
                                    fmt("{\"type\":\"message_start\",\"message\":{\"id\":\"%s\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"%s\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}",
                                            messageId, escapeJsonValue(model)));
                            messageStartSent = true;
                        }
                    }
                    case "response.output_item.added" -> {
                        String itemType = root.has("item") && root.get("item").has("type")
                                ? root.get("item").get("type").asText() : "";
                        int outputIndex = root.has("output_index") ? root.get("output_index").asInt() : 0;

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
                        String text = root.has("delta") ? root.get("delta").asText() : "";
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
                        String text = root.has("text") ? root.get("text").asText() : "";
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
                        String partType = part.path("type").asText("");
                        int key = contentKey(root);
                        if ("output_text".equals(partType) && !textDeltasSeen.contains(key)) {
                            String text = part.path("text").asText("");
                            if (!text.isEmpty()) {
                                emitTextBlockDelta(output, text);
                                textDeltasSeen.add(key);
                            }
                        } else if ("refusal".equals(partType) && !refusalDeltasSeen.contains(key)) {
                            String refusal = part.path("refusal").asText("");
                            if (!refusal.isEmpty()) {
                                emitTextBlockDelta(output, refusal);
                                refusalDeltasSeen.add(key);
                            }
                        }
                        closeCurrentBlock(output);
                    }
                    case "response.refusal.delta" -> {
                        String delta = root.has("delta") ? root.get("delta").asText() : "";
                        if (!delta.isEmpty()) {
                            refusalDeltasSeen.add(contentKey(root));
                            emitTextBlockDelta(output, delta);
                        }
                    }
                    case "response.refusal.done" -> {
                        String refusal = root.has("refusal") ? root.get("refusal").asText() : "";
                        int key = contentKey(root);
                        if (!refusal.isEmpty() && !refusalDeltasSeen.contains(key)) {
                            emitTextBlockDelta(output, refusal);
                            refusalDeltasSeen.add(key);
                        }
                        closeCurrentBlock(output);
                    }
                    case "response.function_call_arguments.delta" -> {
                        String delta = root.has("delta") ? root.get("delta").asText() : "";
                        if (!delta.isEmpty()) {
                            int outputIdx = root.has("output_index") ? root.get("output_index").asInt() : 0;
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
                        int outputIdx = root.has("output_index") ? root.get("output_index").asInt() : 0;
                        if (!outputIndexToBlockIdx.containsKey(outputIdx)) {
                            log.debug("IR→Anthropic stream: function_call arguments done without valid tool block ignored");
                            return output;
                        }
                        if ("Read".equals(currentToolName) && !currentToolHadDelta) {
                            // Read 工具没有实时 delta：发送完整 arguments
                            String args = root.has("arguments") ? root.get("arguments").asText() : currentToolArgs;
                            if (currentToolName != null) {
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
                        String delta = root.has("delta") ? root.get("delta").asText() : "";
                        if (!delta.isEmpty()) {
                            int outputIdx = root.has("output_index") ? root.get("output_index").asInt() : 0;
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
                            appendEvent(output, "content_block_start",
                                    fmt("{\"type\":\"content_block_start\",\"index\":%d,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"%s\",\"name\":\"web_search\",\"input\":{}}}",
                                            contentBlockIndex, escapeJsonValue(srvId)));
                            appendEvent(output, "content_block_stop",
                                    fmt("{\"type\":\"content_block_stop\",\"index\":%d}", contentBlockIndex));
                            contentBlockIndex++;
                            appendEvent(output, "content_block_start",
                                    fmt("{\"type\":\"content_block_start\",\"index\":%d,\"content_block\":{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"%s\",\"content\":[]}}",
                                            contentBlockIndex, escapeJsonValue(srvId)));
                            appendEvent(output, "content_block_stop",
                                    fmt("{\"type\":\"content_block_stop\",\"index\":%d}", contentBlockIndex));
                        } else if (root.has("item")) {
                            int outputIndex = root.has("output_index") ? root.get("output_index").asInt() : 0;
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
                            if (u.has("input_tokens")) usageInput = u.get("input_tokens").asInt();
                            if (u.has("output_tokens")) usageOutput = u.get("output_tokens").asInt();
                            if (u.has("input_tokens_details") && u.get("input_tokens_details").has("cached_tokens")) {
                                usageCached = u.get("input_tokens_details").get("cached_tokens").asInt();
                            }
                        } else if (root.has("response") && root.get("response").has("usage")) {
                            JsonNode u = root.get("response").get("usage");
                            if (u.has("input_tokens")) usageInput = u.get("input_tokens").asInt();
                            if (u.has("output_tokens")) usageOutput = u.get("output_tokens").asInt();
                            if (u.has("input_tokens_details") && u.get("input_tokens_details").has("cached_tokens")) {
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
                            String respStatus = resp.has("status") ? resp.get("status").asText() : "";
                            if ("incomplete".equals(respStatus)) {
                                String incompleteReason = resp.has("incomplete_details")
                                        && resp.get("incomplete_details").has("reason")
                                        ? resp.get("incomplete_details").get("reason").asText() : "";
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
            String itemType = item.path("type").asText("");
            if ("message".equals(itemType) && item.has("content") && item.get("content").isArray()) {
                for (int contentIndex = 0; contentIndex < item.get("content").size(); contentIndex++) {
                    JsonNode part = item.get("content").get(contentIndex);
                    String partType = part.path("type").asText("");
                    int key = outputIndex * 10_000 + contentIndex;
                    if ("output_text".equals(partType) && !textDeltasSeen.contains(key)) {
                        String text = part.path("text").asText("");
                        if (!text.isEmpty()) {
                            emitTextBlockDelta(output, text);
                            textDeltasSeen.add(key);
                        }
                    } else if ("refusal".equals(partType) && !refusalDeltasSeen.contains(key)) {
                        String refusal = part.path("refusal").asText("");
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
                String arguments = item.path("arguments").asText("");
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
            if (root.has("output_index")) {
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
            int outputIdx = root.has("output_index") ? root.get("output_index").asInt() : 0;
            int contentIdx = root.has("content_index") ? root.get("content_index").asInt() : 0;
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
        String output = item.has("output") ? item.get("output").asText() : "";
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
                String partType = part.has("type") ? part.get("type").asText() : "";
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
                        String imageUrl = part.has("image_url") ? part.get("image_url").asText() : "";
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
                    case "tool_result" -> blocks.add(part);
                    default ->
                        log.debug("Responses→Anthropic: unknown user content part type '{}'", partType);
                }
            }
            if (blocks.size() == 0) return null;
            return blocks;
        }
        return content;
    }

    /**
     * Responses input_file content part → Anthropic document content block.
     * The conversion is limited to file_url, file_data, and file_id because those
     * are the stable file payload shapes both protocols can represent.
     */
    private static ObjectNode responsesInputFileToAnthropicDocument(JsonNode part) {
        if (part == null || !part.isObject()) return null;

        ObjectNode source = JSON.createObjectNode();
        if (part.has("file_url") && !part.get("file_url").asText().isBlank()) {
            source.put("type", "url");
            source.put("url", part.get("file_url").asText());
        } else if (part.has("file_data") && !part.get("file_data").asText().isBlank()) {
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
        } else if (part.has("file_id") && !part.get("file_id").asText().isBlank()) {
            source.put("type", "file");
            source.put("file_id", part.get("file_id").asText());
        } else {
            return null;
        }

        ObjectNode documentBlock = JSON.createObjectNode();
        documentBlock.put("type", "document");
        if (part.has("filename") && !part.get("filename").asText().isBlank()) {
            documentBlock.put("title", part.get("filename").asText());
        }
        documentBlock.set("source", source);
        return documentBlock;
    }

    /**
     * Responses assistant content → Anthropic content。
     */
    private static JsonNode convertAssistantContentToAnthropic(JsonNode content) {
        if (content == null) return null;
        if (content.isTextual()) {
            if (content.asText().isEmpty()) return null;
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
                String partType = part.has("type") ? part.get("type").asText() : "";
                if ("output_text".equals(partType) || "text".equals(partType)) {
                    String text = part.has("text") ? part.get("text").asText() : "";
                    if (text.isEmpty()) {
                        continue;
                    }
                    ObjectNode textBlock = JSON.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", text);
                    blocks.add(textBlock);
                } else if ("thinking".equals(partType) || "redacted_thinking".equals(partType)) {
                    blocks.add(part);
                } else if ("tool_use".equals(partType)) {
                    // tool_use 块透传
                    blocks.add(part);
                }
            }
            if (blocks.size() == 0) return null;
            return blocks;
        }
        return content;
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
            copyObjectIfExists(tool, ws, "user_location");
            return ws;
        }
        // function tool → Anthropic tool
        if ("function".equals(toolType)) {
            if (isBlankText(tool.get("name"))) return null;
            ObjectNode func = JSON.createObjectNode();
            func.put("name", tool.get("name").asText());
            if (tool.has("description")) func.put("description", tool.get("description").asText());
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
     * xhigh→max，其余透传。
     */
    private static String mapResponsesEffortToAnthropic(String effort) {
        return "xhigh".equals(effort) ? "max" : effort;
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
            if (ir.has("incomplete_details") && ir.get("incomplete_details").has("reason")
                    && "max_output_tokens".equals(ir.get("incomplete_details").get("reason").asText())) {
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
                String type = part.path("type").asText("");
                if (("reasoning_text".equals(type) || "text".equals(type)) && part.has("text")) {
                    if (text.length() > 0) text.append("\n");
                    text.append(part.get("text").asText());
                }
            }
        }
        if (text.length() > 0) return text.toString();

        if (item.has("summary") && item.get("summary").isArray()) {
            for (JsonNode summary : item.get("summary")) {
                if (!"summary_text".equals(summary.path("type").asText("")) || !summary.has("text")) continue;
                if (text.length() > 0) text.append("\n");
                text.append(summary.get("text").asText());
            }
        }
        return text.toString();
    }

    private static void addReasoningBlocks(ArrayNode content, JsonNode item) {
        String text = extractReasoningText(item);
        String encryptedContent = item.path("encrypted_content").asText("");

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

    private static boolean isBlankText(JsonNode node) {
        return node == null || node.isNull() || node.asText("").isBlank();
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
