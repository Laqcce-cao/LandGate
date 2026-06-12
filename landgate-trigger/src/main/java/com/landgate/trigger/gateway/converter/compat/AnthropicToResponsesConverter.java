package com.landgate.trigger.gateway.converter.compat;

import com.landgate.trigger.gateway.converter.StreamTranslator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.trigger.gateway.converter.StreamTranslator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Anthropic Messages API → OpenAI Responses API（IR）转换器。
 * <p>
 * 负责请求转换、非流式响应转换、流式 SSE 翻译。
 * 由 {@link AnthropicConverter} 门面委托调用，不作为独立 Spring Bean。
 *
 * <p>参照：sub2api {@code anthropic_to_responses.go} + {@code anthropic_to_responses_response.go}
 */
@Slf4j
public class AnthropicToResponsesConverter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MIN_MAX_OUTPUT_TOKENS = 128;
    // ========================
    // 请求转换：Anthropic → Responses IR
    // ========================

    /**
     * 将 Anthropic Messages 请求转换为 Responses API 请求（IR）。
     *
     * @param body Anthropic 请求 JSON 字符串
     * @return Responses 格式的 JsonNode
     */
    public JsonNode requestToIR(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();
            boolean parallelToolCallsDisabled = false;

            // --- 基础字段透传 ---
            copyTextIfExists(src, dst, "model");
            copyBooleanIfExists(src, dst, "stream");

            // 协议转换层不根据模型名猜能力；采样参数按客户端显式请求保留。
            copyNumberIfExists(src, dst, "temperature");
            copyNumberIfExists(src, dst, "top_p");

            // max_tokens → max_output_tokens. sub2api clamps tiny values because
            // OpenAI Responses/Codex rejects very small output budgets.
            if (src.has("max_tokens") && isPositiveInt(src.get("max_tokens"))) {
                dst.put("max_output_tokens", Math.max(src.get("max_tokens").asInt(), MIN_MAX_OUTPUT_TOKENS));
            }

            // stop_sequences → 内部 IR 扩展。Responses 上游不直接消费，跨协议转 Chat/Anthropic 时再还原。
            if (src.has("stop_sequences") && src.get("stop_sequences").isArray()) {
                JsonNode stopSequences = normalizeStopSequences(src.get("stop_sequences"));
                if (stopSequences != null) {
                    dst.set("_landgate_stop_sequences", stopSequences);
                }
            }

            // --- system + messages → input[] ---
            ArrayNode input = JSON.createArrayNode();

            // system → developer message（input[0]）
            if (src.has("system")) {
                List<String> systemTexts = extractSystemTexts(src.get("system"));
                if (!systemTexts.isEmpty()) {
                    ObjectNode developerMsg = JSON.createObjectNode();
                    developerMsg.put("type", "message");
                    developerMsg.put("role", "developer");
                    ArrayNode devContent = JSON.createArrayNode();
                    for (String text : systemTexts) {
                        ObjectNode part = JSON.createObjectNode();
                        part.put("type", "input_text");
                        part.put("text", text);
                        devContent.add(part);
                    }
                    developerMsg.set("content", devContent);
                    input.add(developerMsg);
                }
            }

            // messages[] → input[]
            if (src.has("messages") && src.get("messages").isArray()) {
                for (JsonNode msg : src.get("messages")) {
                    String role = msg.has("role") ? msg.get("role").asText() : "user";
                    JsonNode contentNode = msg.get("content");

                    switch (role) {
                        case "user" -> convertUserMessage(contentNode, input);
                        case "assistant" -> convertAssistantMessage(contentNode, input);
                        default -> convertUserMessage(contentNode, input); // 未知 role fallback 到 user
                    }
                }
            }
            dst.set("input", input);

            // --- tools ---
            if (src.has("tools") && src.get("tools").isArray()) {
                ArrayNode responsesTools = JSON.createArrayNode();
                for (JsonNode tool : src.get("tools")) {
                    JsonNode convertedTool = convertAnthropicToolToResponses(tool);
                    if (convertedTool != null) {
                        responsesTools.add(convertedTool);
                    }
                }
                if (responsesTools.size() > 0) {
                    dst.set("tools", responsesTools);
                }
            }

            // --- tool_choice ---
            if (src.has("tool_choice")) {
                JsonNode disableParallel = src.get("tool_choice").path("disable_parallel_tool_use");
                if (disableParallel.isBoolean() && disableParallel.asBoolean()) {
                    parallelToolCallsDisabled = true;
                }
                JsonNode toolChoice = convertAnthropicToolChoiceToResponses(src.get("tool_choice"));
                if (toolChoice != null) {
                    dst.set("tool_choice", toolChoice);
                }
            }

            // --- output_config.effort → reasoning.effort ---
            // 对齐 sub2api/Codex bridge：普通 Messages 请求也默认带 medium reasoning shape。
            String reasoningEffort = "medium";
            if (src.has("output_config") && src.get("output_config").isObject()
                    && src.get("output_config").has("effort")) {
                reasoningEffort = mapAnthropicEffortToResponses(src.get("output_config").get("effort"));
            }
            if (reasoningEffort == null) {
                reasoningEffort = "medium";
            }

            // --- Codex/Responses 默认形状，对齐 sub2api anthropic bridge ---
            dst.put("store", false);
            ObjectNode text = JSON.createObjectNode();
            text.put("verbosity", "medium");
            dst.set("text", text);
            ObjectNode reasoning = JSON.createObjectNode();
            reasoning.put("effort", reasoningEffort);
            reasoning.put("summary", "auto");
            dst.set("reasoning", reasoning);
            ArrayNode include = JSON.createArrayNode();
            include.add("reasoning.encrypted_content");
            dst.set("include", include);
            if (!parallelToolCallsDisabled) {
                dst.put("parallel_tool_calls", true);
            }
            if (parallelToolCallsDisabled) {
                dst.put("parallel_tool_calls", false);
            }

            return dst;
        } catch (Exception e) {
            log.warn("Anthropic→Responses requestToIR error: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    // ========================
    // 非流式响应转换：Anthropic → Responses IR
    // ========================

    /**
     * 将 Anthropic 非流式响应转换为 Responses 响应（IR）。
     */
    public JsonNode responseToIR(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            dst.put("id", textOrDefault(src.get("id"),
                    "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24)));
            dst.put("object", "response");
            dst.put("model", textOrDefault(src.get("model"), "unknown"));

            // content[] → output[]
            ArrayNode output = JSON.createArrayNode();
            List<JsonNode> textBlocks = new ArrayList<>();

            if (src.has("content") && src.get("content").isArray()) {
                for (JsonNode block : src.get("content")) {
                    String blockType = textOrDefault(block.get("type"), "text");
                    switch (blockType) {
                        case "text" -> textBlocks.add(block);
                        case "thinking" -> {
                            flushTextBlocks(output, textBlocks);
                            ObjectNode reasoning = convertAnthropicThinkingToResponsesReasoning(block);
                            if (hasReasoningPayload(reasoning)) {
                                output.add(reasoning);
                            }
                        }
                        case "redacted_thinking" -> {
                            flushTextBlocks(output, textBlocks);
                            ObjectNode reasoning = convertAnthropicRedactedThinkingToResponsesReasoning(block);
                            if (hasReasoningPayload(reasoning)) {
                                output.add(reasoning);
                            }
                        }
                        case "tool_use" -> {
                            if (isBlankText(block.get("id")) || isBlankText(block.get("name"))) {
                                log.debug("Anthropic→Responses: tool_use missing id or name, ignored");
                            } else {
                                flushTextBlocks(output, textBlocks);
                                ObjectNode funcCall = JSON.createObjectNode();
                                funcCall.put("type", "function_call");
                                funcCall.put("call_id", block.get("id").asText());
                                funcCall.put("name", block.get("name").asText());
                                funcCall.put("arguments", block.has("input") ? block.get("input").toString() : "{}");
                                funcCall.put("status", "completed");
                                output.add(funcCall);
                            }
                        }
                        case "server_tool_use" ->
                            log.debug("Anthropic→Responses: server_tool_use block ignored in non-streaming");
                        default ->
                            log.debug("Anthropic→Responses: unknown block type '{}' ignored", blockType);
                    }
                }
            }

            flushTextBlocks(output, textBlocks);
            if (output.size() == 0) {
                ObjectNode msgItem = JSON.createObjectNode();
                msgItem.put("type", "message");
                msgItem.put("role", "assistant");
                msgItem.put("status", "completed");
                ArrayNode msgContent = JSON.createArrayNode();
                ObjectNode part = JSON.createObjectNode();
                part.put("type", "output_text");
                part.put("text", "");
                msgContent.add(part);
                msgItem.set("content", msgContent);
                output.add(msgItem);
            }

            dst.set("output", output);

            // stop_reason → status
            String stopReason = textOrDefault(src.get("stop_reason"), "end_turn");
            String status = mapAnthropicStopReasonToResponsesStatus(stopReason);
            dst.put("status", status);
            if ("max_tokens".equals(stopReason)) {
                ObjectNode incompleteDetails = JSON.createObjectNode();
                incompleteDetails.put("reason", "max_output_tokens");
                dst.set("incomplete_details", incompleteDetails);
            }

            // usage
            if (src.has("usage")) {
                JsonNode usage = src.get("usage");
                ObjectNode respUsage = JSON.createObjectNode();
                int inputTokens = nonNegativeIntOrZero(usage.get("input_tokens"));
                int outputTokens = nonNegativeIntOrZero(usage.get("output_tokens"));
                int cachedTokens = nonNegativeIntOrZero(usage.get("cache_read_input_tokens"));
                int responseInputTokens = inputTokens;
                respUsage.put("input_tokens", responseInputTokens);
                respUsage.put("output_tokens", outputTokens);
                respUsage.put("total_tokens", responseInputTokens + outputTokens);
                if (cachedTokens > 0) {
                    ObjectNode details = JSON.createObjectNode();
                    details.put("cached_tokens", cachedTokens);
                    respUsage.set("input_tokens_details", details);
                }
                dst.set("usage", respUsage);
            }

            return dst;
        } catch (Exception e) {
            log.warn("Anthropic→Responses responseToIR error: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    // ========================
    // 流式 SSE 翻译：Anthropic SSE → Responses SSE（IR）
    // ========================

    /**
     * 创建 Anthropic SSE → Responses SSE 流式翻译器。
     */
    public StreamTranslator createStreamToIR(String model) {
        return new AnthropicToIRStreamTranslator(model);
    }

    private static void flushTextBlocks(ArrayNode output, List<JsonNode> textBlocks) {
        if (textBlocks.isEmpty()) return;

        ArrayNode msgContent = JSON.createArrayNode();
        for (JsonNode tb : textBlocks) {
            String text = tb.has("text") ? tb.get("text").asText() : "";
            if (text.isEmpty()) {
                continue;
            }
            ObjectNode part = JSON.createObjectNode();
            part.put("type", "output_text");
            part.put("text", text);
            msgContent.add(part);
        }
        if (msgContent.size() > 0) {
            ObjectNode msgItem = JSON.createObjectNode();
            msgItem.put("type", "message");
            msgItem.put("role", "assistant");
            msgItem.put("status", "completed");
            msgItem.set("content", msgContent);
            output.add(msgItem);
        }
        textBlocks.clear();
    }

    // ========================
    // 流式翻译器内部类
    // ========================

    /**
     * Anthropic SSE 事件 → Responses SSE 事件（IR）。
     * <p>
     * 状态机跟踪当前输出 item 类型、sequence_number，将 Anthropic 的
     * content_block_start/delta/stop 事件翻译为对应的 Responses SSE 事件。
     */
    static class AnthropicToIRStreamTranslator implements StreamTranslator {

        private enum ItemType { NONE, MESSAGE, FUNCTION_CALL, REASONING }

        private boolean done = false;
        private boolean createdSent = false;
        private final String responseId;
        private String model;
        private final long createdAt;
        private long sequenceNumber = 0;

        private int outputIndex = -1;
        private ItemType currentItemType = ItemType.NONE;
        private String currentItemId;
        private String currentCallId;
        private String currentName;
        private final StringBuilder currentArguments = new StringBuilder();
        private int contentIndex = 0;
        private boolean messageItemOpen = false;

        private int inputTokens = 0;
        private int outputTokens = 0;
        private int cacheReadInputTokens = 0;

        AnthropicToIRStreamTranslator(String model) {
            this.model = model != null ? model : "unknown";
            this.responseId = "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            this.createdAt = System.currentTimeMillis() / 1000;
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
                    case "message_start" -> {
                        if (root.has("message")) {
                            JsonNode msg = root.get("message");
                            if (!isBlankText(msg.get("model"))) model = msg.get("model").asText();
                            if (msg.has("usage") && isNonNegativeInt(msg.get("usage").get("input_tokens"))) {
                                inputTokens = msg.get("usage").get("input_tokens").asInt();
                            }
                        }
                        ensureCreatedSent(output);
                    }
                    case "content_block_start" -> {
                        JsonNode block = root.has("content_block") ? root.get("content_block") : root;
                        String blockType = textOrDefault(block.get("type"), "text");

                        // 关闭上一个 item
                        closeCurrentItem(output);

                        switch (blockType) {
                            case "text" -> {
                                if (!messageItemOpen) {
                                    outputIndex++;
                                    currentItemId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                                    currentItemType = ItemType.MESSAGE;
                                    contentIndex = 0;

                                    ensureCreatedSent(output);
                                    appendEvent(output, "response.output_item.added",
                                            fmt("{\"type\":\"response.output_item.added\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":%d,\"item\":{\"id\":\"%s\",\"type\":\"message\",\"status\":\"in_progress\",\"role\":\"assistant\",\"content\":[]}}",
                                                    sequenceNumber++, responseId, outputIndex, currentItemId));
                                    messageItemOpen = true;
                                }
                                // 多个 text block 复用同一个 message item
                            }
                            case "thinking" -> {
                                outputIndex++;
                                currentItemId = "rsn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                                currentItemType = ItemType.REASONING;

                                ensureCreatedSent(output);
                                appendEvent(output, "response.output_item.added",
                                        fmt("{\"type\":\"response.output_item.added\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":%d,\"item\":{\"id\":\"%s\",\"type\":\"reasoning\",\"status\":\"in_progress\",\"summary\":[]}}",
                                                sequenceNumber++, responseId, outputIndex, currentItemId));
                            }
                            case "tool_use" -> {
                                if (isBlankText(block.get("id")) || isBlankText(block.get("name"))) {
                                    log.debug("Anthropic→IR stream: tool_use missing id or name, ignored");
                                    currentItemType = ItemType.NONE;
                                    currentItemId = null;
                                    currentCallId = null;
                                    currentName = null;
                                    currentArguments.setLength(0);
                                    break;
                                }
                                outputIndex++;
                                currentItemId = "item_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                                currentItemType = ItemType.FUNCTION_CALL;
                                currentCallId = block.get("id").asText();
                                currentName = block.get("name").asText();
                                currentArguments.setLength(0);
                                if (block.has("input") && !block.get("input").isNull()
                                        && !(block.get("input").isObject() && block.get("input").size() == 0)) {
                                    currentArguments.append(block.get("input").toString());
                                }

                                ensureCreatedSent(output);
                                appendEvent(output, "response.output_item.added",
                                        fmt("{\"type\":\"response.output_item.added\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":%d,\"item\":{\"id\":\"%s\",\"type\":\"function_call\",\"call_id\":\"%s\",\"name\":\"%s\",\"status\":\"in_progress\"}}",
                                                sequenceNumber++, responseId, outputIndex, currentItemId,
                                                escapeJsonValue(currentCallId), escapeJsonValue(currentName)));
                            }
                            case "server_tool_use" ->
                                log.debug("Anthropic→IR stream: server_tool_use block ignored");
                            default ->
                                log.debug("Anthropic→IR stream: unknown content_block_start type '{}'", blockType);
                        }
                    }
                    case "content_block_delta" -> {
                        JsonNode delta = root.has("delta") ? root.get("delta") : root;
                        String deltaType = textOrDefault(delta.get("type"), "");

                        switch (deltaType) {
                            case "text_delta" -> {
                                String text = textOrDefault(delta.get("text"), "");
                                if (!text.isEmpty()) {
                                    appendEvent(output, "response.output_text.delta",
                                            fmt("{\"type\":\"response.output_text.delta\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"content_index\":%d,\"delta\":\"%s\"}",
                                                    sequenceNumber++, responseId, currentItemId, outputIndex, contentIndex,
                                                    escapeJsonValue(text)));
                                }
                            }
                            case "thinking_delta" -> {
                                String thinking = textOrDefault(delta.get("thinking"), "");
                                if (!thinking.isEmpty()) {
                                    appendEvent(output, "response.reasoning_summary_text.delta",
                                            fmt("{\"type\":\"response.reasoning_summary_text.delta\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"summary_index\":0,\"delta\":\"%s\"}",
                                                    sequenceNumber++, responseId, currentItemId, outputIndex,
                                                    escapeJsonValue(thinking)));
                                }
                            }
                            case "input_json_delta" -> {
                                String partialJson = textOrDefault(delta.get("partial_json"), "");
                                if (!partialJson.isEmpty() && currentItemType == ItemType.FUNCTION_CALL) {
                                    currentArguments.append(partialJson);
                                    appendEvent(output, "response.function_call_arguments.delta",
                                            fmt("{\"type\":\"response.function_call_arguments.delta\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"delta\":\"%s\"}",
                                                    sequenceNumber++, responseId, currentItemId, outputIndex,
                                                    escapeJsonValue(partialJson)));
                                }
                            }
                            case "signature_delta" -> {
                                // Anthropic signatures have no Responses stream equivalent in sub2api.
                            }
                            default -> log.debug("Anthropic→IR stream: unknown delta type '{}'", deltaType);
                        }
                    }
                    case "content_block_stop" -> {
                        if (currentItemType == ItemType.REASONING) {
                            appendEvent(output, "response.reasoning_summary_text.done",
                                    fmt("{\"type\":\"response.reasoning_summary_text.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"summary_index\":0}",
                                            sequenceNumber++, responseId, currentItemId, outputIndex));
                            appendEvent(output, "response.output_item.done",
                                    fmt("{\"type\":\"response.output_item.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":%d,\"item\":{\"id\":\"%s\",\"type\":\"reasoning\",\"status\":\"completed\"}}",
                                            sequenceNumber++, responseId, outputIndex, currentItemId));
                            currentItemType = ItemType.NONE;
                        } else if (currentItemType == ItemType.FUNCTION_CALL) {
                            String arguments = currentArguments.length() > 0 ? currentArguments.toString() : "{}";
                            appendEvent(output, "response.function_call_arguments.done",
                                    fmt("{\"type\":\"response.function_call_arguments.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d}",
                                            sequenceNumber++, responseId, currentItemId, outputIndex));
                            appendEvent(output, "response.output_item.done",
                                    fmt("{\"type\":\"response.output_item.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":%d,\"item\":{\"id\":\"%s\",\"type\":\"function_call\",\"call_id\":\"%s\",\"name\":\"%s\",\"arguments\":\"%s\",\"status\":\"completed\"}}",
                                            sequenceNumber++, responseId, outputIndex, currentItemId,
                                            escapeJsonValue(currentCallId), escapeJsonValue(currentName),
                                            escapeJsonValue(arguments)));
                            currentItemType = ItemType.NONE;
                            currentArguments.setLength(0);
                        } else if (currentItemType == ItemType.MESSAGE) {
                            // text block stop: 只发送 text.done，不关闭 message item
                            appendEvent(output, "response.output_text.done",
                                    fmt("{\"type\":\"response.output_text.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"content_index\":%d}",
                                            sequenceNumber++, responseId, currentItemId, outputIndex, contentIndex));
                        }
                    }
                    case "message_delta" -> {
                        if (root.has("usage") && isNonNegativeInt(root.get("usage").get("output_tokens"))) {
                            outputTokens = root.get("usage").get("output_tokens").asInt();
                        }
                        if (root.has("usage") && isNonNegativeInt(root.get("usage").get("cache_read_input_tokens"))) {
                            cacheReadInputTokens = root.get("usage").get("cache_read_input_tokens").asInt();
                        }
                    }
                    case "message_stop" -> {
                        closeCurrentItem(output);
                        String status = "completed";
                        String cacheDetails = cacheReadInputTokens > 0
                                ? fmt(",\"input_tokens_details\":{\"cached_tokens\":%d}", cacheReadInputTokens)
                                : "";
                        int responseInputTokens = inputTokens;
                        appendEvent(output, "response.completed",
                                fmt("{\"type\":\"%s\",\"sequence_number\":%d,\"response\":{\"id\":\"%s\",\"object\":\"response\",\"model\":\"%s\",\"created_at\":%d,\"status\":\"%s\"%s,\"usage\":{\"input_tokens\":%d,\"output_tokens\":%d,\"total_tokens\":%d%s}}}",
                                        "response.completed", sequenceNumber++, responseId, escapeJsonValue(model), createdAt,
                                        status, "",
                                        responseInputTokens, outputTokens, responseInputTokens + outputTokens, cacheDetails));
                        done = true;
                    }
                    case "error" -> {
                        JsonNode error = root.path("error");
                        String errorType = textOrDefault(error.get("type"), "api_error");
                        String message = textOrDefault(error.get("message"), "Anthropic stream error");
                        ensureCreatedSent(output);
                        String cacheDetails = cacheReadInputTokens > 0
                                ? fmt(",\"input_tokens_details\":{\"cached_tokens\":%d}", cacheReadInputTokens)
                                : "";
                        int responseInputTokens = inputTokens;
                        appendEvent(output, "response.failed",
                                fmt("{\"type\":\"response.failed\",\"sequence_number\":%d,\"response\":{\"id\":\"%s\",\"object\":\"response\",\"model\":\"%s\",\"created_at\":%d,\"status\":\"failed\",\"error\":{\"code\":\"%s\",\"message\":\"%s\"},\"usage\":{\"input_tokens\":%d,\"output_tokens\":%d,\"total_tokens\":%d%s}}}",
                                        sequenceNumber++, responseId, escapeJsonValue(model), createdAt,
                                        escapeJsonValue(errorType), escapeJsonValue(message),
                                        responseInputTokens, outputTokens, responseInputTokens + outputTokens, cacheDetails));
                        done = true;
                    }
                }
            } catch (Exception e) {
                log.debug("Anthropic→IR SSE error: {}", e.getMessage());
            }
            return output;
        }

        private void ensureCreatedSent(List<String> output) {
            if (!createdSent) {
                appendEvent(output, "response.created",
                        fmt("{\"type\":\"response.created\",\"sequence_number\":%d,\"response\":{\"id\":\"%s\",\"object\":\"response\",\"model\":\"%s\",\"created_at\":%d,\"status\":\"in_progress\",\"output\":[],\"usage\":null}}",
                                sequenceNumber++, responseId, escapeJsonValue(model), createdAt));
                createdSent = true;
            }
        }

        private void closeCurrentItem(List<String> output) {
            if (currentItemType == ItemType.MESSAGE && messageItemOpen) {
                appendEvent(output, "response.output_item.done",
                        fmt("{\"type\":\"response.output_item.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":%d,\"item\":{\"id\":\"%s\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\"}}",
                                sequenceNumber++, responseId, outputIndex, currentItemId));
                messageItemOpen = false;
            }
            // function_call 和 reasoning 在 content_block_stop 中已关闭
        }

        @Override public boolean isDone() { return done; }
        @Override public int getInputTokens() { return inputTokens; }
        @Override public int getOutputTokens() { return outputTokens; }
    }

    // ========================
    // 辅助方法
    // ========================

    /**
     * 提取 Anthropic system 字段中的纯文本列表。
     * 过滤 x-anthropic-billing-header: 前缀的文本和空文本。
     */
    private static List<String> extractSystemTexts(JsonNode system) {
        List<String> texts = new ArrayList<>();
        if (system == null) return texts;

        if (system.isTextual()) {
            String text = system.asText();
            if (!text.isEmpty() && !text.startsWith("x-anthropic-billing-header: ")) {
                texts.add(text);
            }
            return texts;
        }

        if (system.isArray()) {
            for (JsonNode block : system) {
                String blockType = textOrDefault(block.get("type"), "");
                if ("text".equals(blockType) && block.has("text")) {
                    String text = textOrDefault(block.get("text"), "");
                    if (!text.isEmpty() && !text.startsWith("x-anthropic-billing-header: ")) {
                        texts.add(text);
                    }
                }
            }
        }
        return texts;
    }

    /**
     * 转换 Anthropic user 消息到 Responses input items。
     * <p>
     * tool_result 块独立产出 function_call_output items；
         * text + image + document 块产出 user message item。
     */
    private static void convertUserMessage(JsonNode contentNode, ArrayNode input) {
        if (contentNode == null) return;

        // 纯字符串
        if (contentNode.isTextual()) {
            ObjectNode item = JSON.createObjectNode();
            item.put("type", "message");
            item.put("role", "user");
            ArrayNode parts = JSON.createArrayNode();
            ObjectNode part = JSON.createObjectNode();
            part.put("type", "input_text");
            part.put("text", contentNode.asText());
            parts.add(part);
            item.set("content", parts);
            input.add(item);
            return;
        }

        if (!contentNode.isArray()) return;

        List<JsonNode> toolResults = new ArrayList<>();
        List<JsonNode> textBlocks = new ArrayList<>();
        List<JsonNode> imageBlocks = new ArrayList<>();
        List<JsonNode> documentBlocks = new ArrayList<>();

        for (JsonNode block : contentNode) {
            String blockType = textOrDefault(block.get("type"), "text");
            switch (blockType) {
                case "tool_result" -> toolResults.add(block);
                case "text" -> textBlocks.add(block);
                case "image" -> imageBlocks.add(block);
                case "document" -> documentBlocks.add(block);
                default -> log.debug("Anthropic→Responses: unknown user block type '{}'", blockType);
            }
        }

        // tool_result → function_call_output items
        List<JsonNode> deferredImages = new ArrayList<>();
        List<JsonNode> deferredDocuments = new ArrayList<>();
        for (JsonNode tr : toolResults) {
            if (isBlankText(tr.get("tool_use_id"))) {
                log.debug("Anthropic→Responses: tool_result missing tool_use_id, ignored");
                continue;
            }
            ObjectNode fco = JSON.createObjectNode();
            fco.put("type", "function_call_output");
            fco.put("call_id", tr.get("tool_use_id").asText());

            // 提取文本，图片和文档作为后续 user content 保留。
            JsonNode trContent = tr.get("content");
            if (trContent != null && trContent.isArray()) {
                StringBuilder textOutput = new StringBuilder();
                for (JsonNode c : trContent) {
                    String cType = textOrDefault(c.get("type"), "text");
                    String text = textOrDefault(c.get("text"), "");
                    if ("text".equals(cType) && !text.isEmpty()) {
                        if (textOutput.length() > 0) textOutput.append("\n");
                        textOutput.append(text);
                    } else if ("image".equals(cType)) {
                        deferredImages.add(c);
                    } else if ("document".equals(cType)) {
                        deferredDocuments.add(c);
                    }
                }
                fco.put("output", textOutput.toString());
            } else if (trContent != null && trContent.isTextual()) {
                String output = trContent.asText();
                fco.put("output", output.isEmpty() ? "(empty)" : output);
            } else {
                fco.put("output", "(empty)");
            }
            if (fco.path("output").asText("").isEmpty()) {
                fco.put("output", "(empty)");
            }
            input.add(fco);
        }

        // text + image + 延迟图片 → user message item
        List<JsonNode> allParts = new ArrayList<>();
        allParts.addAll(textBlocks);
        allParts.addAll(imageBlocks);
        allParts.addAll(documentBlocks);
        allParts.addAll(deferredImages);
        allParts.addAll(deferredDocuments);

        if (!allParts.isEmpty()) {
            ObjectNode item = JSON.createObjectNode();
            item.put("type", "message");
            item.put("role", "user");
            ArrayNode parts = JSON.createArrayNode();
            for (JsonNode p : allParts) {
                String pType = textOrDefault(p.get("type"), "text");
                if ("text".equals(pType)) {
                    String text = textOrDefault(p.get("text"), "");
                    if (text.isEmpty()) {
                        continue;
                    }
                    ObjectNode part = JSON.createObjectNode();
                    part.put("type", "input_text");
                    part.put("text", text);
                    parts.add(part);
                } else if ("image".equals(pType)) {
                    String dataUri = anthropicImageToDataURI(p);
                    if (dataUri != null && !dataUri.isEmpty()) {
                        ObjectNode part = JSON.createObjectNode();
                        part.put("type", "input_image");
                        part.put("image_url", dataUri);
                        parts.add(part);
                    }
                } else if ("document".equals(pType)) {
                    ObjectNode part = anthropicDocumentToResponsesInputFile(p);
                    if (part != null) {
                        parts.add(part);
                    }
                }
            }
            if (parts.size() > 0) {
                item.set("content", parts);
                input.add(item);
            }
        }
    }

    /**
     * Anthropic document content block → Responses input_file content part.
     * Only source forms with an OpenAI Responses input_file equivalent are converted.
     */
    private static ObjectNode anthropicDocumentToResponsesInputFile(JsonNode block) {
        if (block == null || !block.has("source") || !block.get("source").isObject()) return null;
        JsonNode source = block.get("source");
        String sourceType = textOrDefault(source.get("type"), "");
        ObjectNode part = JSON.createObjectNode();
        part.put("type", "input_file");

        if (!isBlankText(block.get("title"))) {
            part.put("filename", block.get("title").asText());
        } else if (!isBlankText(source.get("filename"))) {
            part.put("filename", source.get("filename").asText());
        }

        switch (sourceType) {
            case "url" -> {
                if (isBlankText(source.get("url"))) return null;
                part.put("file_url", source.get("url").asText());
            }
            case "base64" -> {
                if (isBlankText(source.get("data"))) return null;
                String mediaType = !isBlankText(source.get("media_type"))
                        ? source.get("media_type").asText()
                        : "application/pdf";
                part.put("file_data", "data:" + mediaType + ";base64," + source.get("data").asText());
            }
            case "file" -> {
                if (isBlankText(source.get("file_id"))) return null;
                part.put("file_id", source.get("file_id").asText());
            }
            default -> {
                log.debug("Anthropic→Responses: unsupported document source type '{}'", sourceType);
                return null;
            }
        }
        return part;
    }

    /**
     * 转换 Anthropic assistant 消息到 Responses input items。
     * <p>
     * text 块 → message item；tool_use 块 → function_call item；
     * thinking/redacted_thinking 块 → 请求续链时不重放。对齐 sub2api：
     * OpenAI Responses/Codex input 不接受 Anthropic thinking 历史块作为输入。
     */
    private static void convertAssistantMessage(JsonNode contentNode, ArrayNode input) {
        if (contentNode == null) return;

        if (contentNode.isTextual()) {
            ObjectNode item = JSON.createObjectNode();
            item.put("type", "message");
            item.put("role", "assistant");
            ArrayNode parts = JSON.createArrayNode();
            ObjectNode part = JSON.createObjectNode();
            part.put("type", "output_text");
            part.put("text", contentNode.asText());
            parts.add(part);
            item.set("content", parts);
            input.add(item);
            return;
        }

        if (!contentNode.isArray()) return;

        // 收集所有 text 块文本
        StringBuilder textBuilder = new StringBuilder();
        List<JsonNode> toolUses = new ArrayList<>();

        for (JsonNode block : contentNode) {
            String blockType = textOrDefault(block.get("type"), "text");
            switch (blockType) {
                case "text" -> {
                    String text = textOrDefault(block.get("text"), "");
                    if (!text.isEmpty()) {
                        if (textBuilder.length() > 0) textBuilder.append("\n\n");
                        textBuilder.append(text);
                    }
                }
                case "tool_use" -> toolUses.add(block);
                case "thinking", "redacted_thinking" ->
                        log.debug("Anthropic→Responses request: assistant {} block ignored", blockType);
                default -> log.debug("Anthropic→Responses: unknown assistant block type '{}'", blockType);
            }
        }

        // 非空文本 → message item
        String text = textBuilder.toString();
        if (!text.isEmpty()) {
            ObjectNode item = JSON.createObjectNode();
            item.put("type", "message");
            item.put("role", "assistant");
            ArrayNode parts = JSON.createArrayNode();
            ObjectNode part = JSON.createObjectNode();
            part.put("type", "output_text");
            part.put("text", text);
            parts.add(part);
            item.set("content", parts);
            input.add(item);
        }

        // tool_use → function_call items
        for (JsonNode tu : toolUses) {
            if (isBlankText(tu.get("id")) || isBlankText(tu.get("name"))) {
                log.debug("Anthropic→Responses: assistant tool_use missing id or name, ignored");
                continue;
            }
            ObjectNode fco = JSON.createObjectNode();
            fco.put("type", "function_call");
            fco.put("call_id", tu.get("id").asText());
            fco.put("name", tu.get("name").asText());
            fco.put("arguments", tu.has("input") ? tu.get("input").toString() : "{}");
            input.add(fco);
        }
    }

    /**
     * 转换 Anthropic tool 到 Responses tool。
     */
    private static JsonNode convertAnthropicToolToResponses(JsonNode tool) {
        String toolType = textOrDefault(tool.get("type"), "");
        // web_search_ 前缀 → web_search
        if (toolType.startsWith("web_search")) {
            ObjectNode ws = JSON.createObjectNode();
            ws.put("type", "web_search");
            ObjectNode userLocation = normalizeWebSearchUserLocation(tool.get("user_location"));
            if (userLocation != null) {
                ws.set("user_location", userLocation);
            }
            return ws;
        }
        if (isBlankText(tool.get("name"))) {
            return null;
        }
        // 普通 function tool
        ObjectNode func = JSON.createObjectNode();
        func.put("type", "function");
        func.put("name", tool.get("name").asText());
        copyTextIfExists(tool, func, "description");
        // input_schema → parameters（规范化）
        JsonNode schema = tool.has("input_schema") ? tool.get("input_schema") : null;
        func.set("parameters", normalizeToolParameters(schema));
        func.put("strict", false);
        return func;
    }

    /**
     * 规范化 tool parameters schema —— 空/null → {"type":"object","properties":{}}。
     */
    private static JsonNode normalizeToolParameters(JsonNode schema) {
        if (schema == null || schema.isNull() || (schema.isTextual() && "null".equals(schema.asText()))) {
            return createEmptySchema();
        }
        if (schema.isObject()) {
            ObjectNode obj = schema.deepCopy();
            if (!obj.has("properties")) {
                obj.set("properties", JSON.createObjectNode());
            }
            return obj;
        }
        return createEmptySchema();
    }

    private static ObjectNode createEmptySchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", JSON.createObjectNode());
        return schema;
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

    /**
     * 转换 Anthropic tool_choice 到 Responses tool_choice。
     */
    private static JsonNode convertAnthropicToolChoiceToResponses(JsonNode toolChoice) {
        if (toolChoice.isTextual()) {
            return switch (toolChoice.asText()) {
                case "auto" -> JSON.getNodeFactory().textNode("auto");
                case "any" -> JSON.getNodeFactory().textNode("required");
                case "none" -> JSON.getNodeFactory().textNode("none");
                default -> null;
            };
        }

        if (toolChoice.isObject() && toolChoice.has("type")) {
            String tcType = textOrDefault(toolChoice.get("type"), "");
            return switch (tcType) {
                case "auto" -> JSON.getNodeFactory().textNode("auto");
                case "any" -> JSON.getNodeFactory().textNode("required");
                case "none" -> JSON.getNodeFactory().textNode("none");
                case "tool" -> {
                    if (isBlankText(toolChoice.get("name"))) yield null;
                    ObjectNode obj = JSON.createObjectNode();
                    obj.put("type", "function");
                    obj.put("name", toolChoice.get("name").asText());
                    yield obj;
                }
                default -> null;
            };
        }
        return null;
    }

    private static boolean isBlankText(JsonNode node) {
        return node == null || !node.isTextual() || node.asText().isBlank();
    }

    private static String textOrDefault(JsonNode node, String defaultValue) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : defaultValue;
    }

    private static ObjectNode convertAnthropicThinkingToResponsesReasoning(JsonNode block) {
        ObjectNode reasoningItem = JSON.createObjectNode();
        reasoningItem.put("type", "reasoning");
        reasoningItem.put("status", "completed");
        String thinking = textOrDefault(block.get("thinking"), "");

        if (!thinking.isEmpty()) {
            ArrayNode summary = JSON.createArrayNode();
            ObjectNode summaryText = JSON.createObjectNode();
            summaryText.put("type", "summary_text");
            summaryText.put("text", thinking);
            summary.add(summaryText);
            reasoningItem.set("summary", summary);
        }
        return reasoningItem;
    }

    private static ObjectNode convertAnthropicRedactedThinkingToResponsesReasoning(JsonNode block) {
        ObjectNode reasoningItem = JSON.createObjectNode();
        reasoningItem.put("type", "reasoning");
        reasoningItem.put("status", "completed");
        return reasoningItem;
    }

    private static boolean hasReasoningPayload(JsonNode reasoningItem) {
        return reasoningItem.has("content")
                || reasoningItem.has("summary")
                || reasoningItem.has("encrypted_content");
    }

    /**
     * Anthropic effort → Responses effort 映射。
     * low/medium/high 保持同名，xhigh/max→xhigh；未知值不透传。
     */
    private static String mapAnthropicEffortToResponses(JsonNode effort) {
        if (effort == null || !effort.isTextual()) return null;
        return switch (effort.asText()) {
            case "low" -> "low";
            case "medium" -> "medium";
            case "high" -> "high";
            case "xhigh", "max" -> "xhigh";
            default -> effort.asText();
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
     * Anthropic stop_reason → Responses status。
     * max_tokens/model_context_window_exceeded→"incomplete", 其他→"completed"。
     * 只有 max_tokens 有 OpenAI Responses 明确等价的 incomplete_details.reason。
     */
    private static String mapAnthropicStopReasonToResponsesStatus(String stopReason) {
        return switch (stopReason) {
            case "max_tokens", "model_context_window_exceeded" -> "incomplete";
            default -> "completed";
        };
    }

    /**
     * Anthropic image source → data URI 字符串。
     */
    private static String anthropicImageToDataURI(JsonNode imageBlock) {
        if (imageBlock == null || !imageBlock.has("source")) return null;
        JsonNode source = imageBlock.get("source");
        String sourceType = textOrDefault(source.get("type"), "");
        if ("url".equals(sourceType)) {
            return textOrDefault(source.get("url"), null);
        }
        String mediaType = textOrDefault(source.get("media_type"), "image/png");
        String data = textOrDefault(source.get("data"), "");
        if (data.isEmpty()) return null;
        return "data:" + mediaType + ";base64," + data;
    }

    // ========================
    // 通用工具方法
    // ========================

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
