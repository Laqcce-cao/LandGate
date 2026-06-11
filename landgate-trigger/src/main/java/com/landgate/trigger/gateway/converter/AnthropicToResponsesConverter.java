package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

            // --- 基础字段透传 ---
            copyIfExists(src, dst, "model");
            copyIfExists(src, dst, "stream");

            // temperature / top_p：仅对非推理模型透传（gpt-5 前缀跳过）
            String model = src.has("model") ? src.get("model").asText() : "";
            if (!isReasoningModel(model)) {
                copyIfExists(src, dst, "temperature");
                copyIfExists(src, dst, "top_p");
            }

            // max_tokens → max_output_tokens（最小 128）
            if (src.has("max_tokens")) {
                int maxTokens = src.get("max_tokens").asInt();
                dst.put("max_output_tokens", Math.max(maxTokens, MIN_MAX_OUTPUT_TOKENS));
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
                    responsesTools.add(convertAnthropicToolToResponses(tool));
                }
                dst.set("tools", responsesTools);
            }

            // --- tool_choice ---
            if (src.has("tool_choice")) {
                dst.set("tool_choice", convertAnthropicToolChoiceToResponses(src.get("tool_choice")));
            }

            // --- reasoning: output_config.effort → reasoning.effort（默认 medium） ---
            String effort = "medium";
            if (src.has("output_config") && src.get("output_config").has("effort")) {
                String rawEffort = src.get("output_config").get("effort").asText();
                if (rawEffort != null && !rawEffort.isEmpty()) {
                    effort = mapAnthropicEffortToResponses(rawEffort);
                }
            }
            ObjectNode reasoning = JSON.createObjectNode();
            reasoning.put("effort", effort);
            reasoning.put("summary", "auto");
            dst.set("reasoning", reasoning);

            // --- 固定字段 ---
            dst.put("store", false);
            ArrayNode include = JSON.createArrayNode();
            include.add("reasoning.encrypted_content");
            dst.set("include", include);
            dst.put("parallel_tool_calls", true);
            ObjectNode textConfig = JSON.createObjectNode();
            textConfig.put("verbosity", "medium");
            dst.set("text", textConfig);

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

            dst.put("id", src.has("id") ? src.get("id").asText()
                    : "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            dst.put("object", "response");
            dst.put("model", src.has("model") ? src.get("model").asText() : "unknown");

            // content[] → output[]
            ArrayNode output = JSON.createArrayNode();
            List<JsonNode> textBlocks = new ArrayList<>();

            if (src.has("content") && src.get("content").isArray()) {
                for (JsonNode block : src.get("content")) {
                    String blockType = block.has("type") ? block.get("type").asText() : "text";
                    switch (blockType) {
                        case "text" -> textBlocks.add(block);
                        case "thinking" -> {
                            // thinking → reasoning output item
                            ObjectNode reasoningItem = JSON.createObjectNode();
                            reasoningItem.put("type", "reasoning");
                            reasoningItem.put("status", "completed");
                            ArrayNode summary = JSON.createArrayNode();
                            ObjectNode summaryText = JSON.createObjectNode();
                            summaryText.put("type", "summary_text");
                            summaryText.put("text", block.has("thinking") ? block.get("thinking").asText() : "");
                            summary.add(summaryText);
                            reasoningItem.set("summary", summary);
                            output.add(reasoningItem);
                        }
                        case "tool_use" -> {
                            ObjectNode funcCall = JSON.createObjectNode();
                            funcCall.put("type", "function_call");
                            funcCall.put("call_id", block.has("id") ? block.get("id").asText()
                                    : "toolu_" + UUID.randomUUID());
                            funcCall.put("name", block.has("name") ? block.get("name").asText() : "");
                            funcCall.put("arguments", block.has("input") ? block.get("input").toString() : "{}");
                            funcCall.put("status", "completed");
                            output.add(funcCall);
                        }
                        case "server_tool_use" ->
                            log.debug("Anthropic→Responses: server_tool_use block ignored in non-streaming");
                        default ->
                            log.debug("Anthropic→Responses: unknown block type '{}' ignored", blockType);
                    }
                }
            }

            // 合并 text 块到单个 message output item
            if (!textBlocks.isEmpty()) {
                ObjectNode msgItem = JSON.createObjectNode();
                msgItem.put("type", "message");
                msgItem.put("role", "assistant");
                msgItem.put("status", "completed");
                ArrayNode msgContent = JSON.createArrayNode();
                for (JsonNode tb : textBlocks) {
                    ObjectNode part = JSON.createObjectNode();
                    part.put("type", "output_text");
                    part.put("text", tb.has("text") ? tb.get("text").asText() : "");
                    msgContent.add(part);
                }
                msgItem.set("content", msgContent);
                output.add(msgItem);
            }

            if (output.size() == 0) {
                // 至少保证有一个 message output
                ObjectNode emptyMsg = JSON.createObjectNode();
                emptyMsg.put("type", "message");
                emptyMsg.put("role", "assistant");
                emptyMsg.put("status", "completed");
                ArrayNode emptyContent = JSON.createArrayNode();
                ObjectNode emptyText = JSON.createObjectNode();
                emptyText.put("type", "output_text");
                emptyText.put("text", "");
                emptyContent.add(emptyText);
                emptyMsg.set("content", emptyContent);
                output.add(emptyMsg);
            }
            dst.set("output", output);

            // stop_reason → status
            String stopReason = src.has("stop_reason") ? src.get("stop_reason").asText() : "end_turn";
            String status = mapAnthropicStopReasonToResponsesStatus(stopReason);
            dst.put("status", status);
            if ("incomplete".equals(status)) {
                ObjectNode incompleteDetails = JSON.createObjectNode();
                incompleteDetails.put("reason", "max_output_tokens");
                dst.set("incomplete_details", incompleteDetails);
            }

            // usage
            if (src.has("usage")) {
                JsonNode usage = src.get("usage");
                ObjectNode respUsage = JSON.createObjectNode();
                int inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
                int outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
                respUsage.put("input_tokens", inputTokens);
                respUsage.put("output_tokens", outputTokens);
                respUsage.put("total_tokens", inputTokens + outputTokens);
                if (usage.has("cache_read_input_tokens") && usage.get("cache_read_input_tokens").asInt() > 0) {
                    ObjectNode details = JSON.createObjectNode();
                    details.put("cached_tokens", usage.get("cache_read_input_tokens").asInt());
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
                String type = root.has("type") ? root.get("type").asText() : null;
                if (type == null) return output;

                switch (type) {
                    case "message_start" -> {
                        if (root.has("message")) {
                            JsonNode msg = root.get("message");
                            if (msg.has("model")) model = msg.get("model").asText();
                            if (msg.has("usage") && msg.get("usage").has("input_tokens")) {
                                inputTokens = msg.get("usage").get("input_tokens").asInt();
                            }
                        }
                        ensureCreatedSent(output);
                    }
                    case "content_block_start" -> {
                        JsonNode block = root.has("content_block") ? root.get("content_block") : root;
                        String blockType = block.has("type") ? block.get("type").asText() : "text";
                        int index = root.has("index") ? root.get("index").asInt() : outputIndex + 1;

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
                                outputIndex++;
                                currentItemId = "item_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                                currentItemType = ItemType.FUNCTION_CALL;
                                currentCallId = block.has("id") ? block.get("id").asText() : "call_" + UUID.randomUUID();
                                currentName = block.has("name") ? block.get("name").asText() : "";

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
                        String deltaType = delta.has("type") ? delta.get("type").asText() : "";

                        switch (deltaType) {
                            case "text_delta" -> {
                                String text = delta.has("text") ? delta.get("text").asText() : "";
                                if (!text.isEmpty()) {
                                    appendEvent(output, "response.output_text.delta",
                                            fmt("{\"type\":\"response.output_text.delta\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"content_index\":%d,\"delta\":\"%s\"}",
                                                    sequenceNumber++, responseId, currentItemId, outputIndex, contentIndex,
                                                    escapeJsonValue(text)));
                                }
                            }
                            case "thinking_delta" -> {
                                String thinking = delta.has("thinking") ? delta.get("thinking").asText() : "";
                                if (!thinking.isEmpty()) {
                                    appendEvent(output, "response.reasoning_summary_text.delta",
                                            fmt("{\"type\":\"response.reasoning_summary_text.delta\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"summary_index\":0,\"delta\":\"%s\"}",
                                                    sequenceNumber++, responseId, currentItemId, outputIndex,
                                                    escapeJsonValue(thinking)));
                                }
                            }
                            case "input_json_delta" -> {
                                String partialJson = delta.has("partial_json") ? delta.get("partial_json").asText() : "";
                                if (!partialJson.isEmpty()) {
                                    appendEvent(output, "response.function_call_arguments.delta",
                                            fmt("{\"type\":\"response.function_call_arguments.delta\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"delta\":\"%s\"}",
                                                    sequenceNumber++, responseId, currentItemId, outputIndex,
                                                    escapeJsonValue(partialJson)));
                                }
                            }
                            case "signature_delta" -> { /* 丢弃：Responses 不使用签名 */ }
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
                            appendEvent(output, "response.function_call_arguments.done",
                                    fmt("{\"type\":\"response.function_call_arguments.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"arguments\":\"%s\"}",
                                            sequenceNumber++, responseId, currentItemId, outputIndex,
                                            escapeJsonValue("{}")));
                            appendEvent(output, "response.output_item.done",
                                    fmt("{\"type\":\"response.output_item.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":%d,\"item\":{\"id\":\"%s\",\"type\":\"function_call\",\"call_id\":\"%s\",\"name\":\"%s\",\"status\":\"completed\"}}",
                                            sequenceNumber++, responseId, outputIndex, currentItemId,
                                            escapeJsonValue(currentCallId), escapeJsonValue(currentName)));
                            currentItemType = ItemType.NONE;
                        } else if (currentItemType == ItemType.MESSAGE) {
                            // text block stop: 只发送 text.done，不关闭 message item
                            appendEvent(output, "response.output_text.done",
                                    fmt("{\"type\":\"response.output_text.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"content_index\":%d,\"text\":\"\"}",
                                            sequenceNumber++, responseId, currentItemId, outputIndex, contentIndex));
                        }
                    }
                    case "message_delta" -> {
                        if (root.has("usage") && root.get("usage").has("output_tokens")) {
                            outputTokens = root.get("usage").get("output_tokens").asInt();
                        }
                        // stop_reason / stop_sequence 不在此阶段处理
                    }
                    case "message_stop" -> {
                        closeCurrentItem(output);
                        // response.completed
                        appendEvent(output, "response.completed",
                                fmt("{\"type\":\"response.completed\",\"sequence_number\":%d,\"response\":{\"id\":\"%s\",\"object\":\"response\",\"model\":\"%s\",\"created_at\":%d,\"status\":\"completed\",\"usage\":{\"input_tokens\":%d,\"output_tokens\":%d,\"total_tokens\":%d}}}",
                                        sequenceNumber++, responseId, escapeJsonValue(model), createdAt,
                                        inputTokens, outputTokens, inputTokens + outputTokens));
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
                String blockType = block.has("type") ? block.get("type").asText() : "";
                if ("text".equals(blockType) && block.has("text")) {
                    String text = block.get("text").asText();
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
     * text + image 块产出 user message item。
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

        for (JsonNode block : contentNode) {
            String blockType = block.has("type") ? block.get("type").asText() : "text";
            switch (blockType) {
                case "tool_result" -> toolResults.add(block);
                case "text" -> textBlocks.add(block);
                case "image" -> imageBlocks.add(block);
                default -> log.debug("Anthropic→Responses: unknown user block type '{}'", blockType);
            }
        }

        // tool_result → function_call_output items
        List<JsonNode> deferredImages = new ArrayList<>();
        for (JsonNode tr : toolResults) {
            ObjectNode fco = JSON.createObjectNode();
            fco.put("type", "function_call_output");
            fco.put("call_id", tr.has("tool_use_id") ? tr.get("tool_use_id").asText() : "");

            // 提取文本和图片
            JsonNode trContent = tr.get("content");
            if (trContent != null && trContent.isArray()) {
                StringBuilder textOutput = new StringBuilder();
                for (JsonNode c : trContent) {
                    String cType = c.has("type") ? c.get("type").asText() : "text";
                    if ("text".equals(cType) && c.has("text")) {
                        if (textOutput.length() > 0) textOutput.append("\n");
                        textOutput.append(c.get("text").asText());
                    } else if ("image".equals(cType)) {
                        deferredImages.add(c);
                    }
                }
                fco.put("output", textOutput.length() > 0 ? textOutput.toString() : "(empty)");
            } else if (trContent != null && trContent.isTextual()) {
                fco.put("output", trContent.asText());
            } else {
                fco.put("output", "(empty)");
            }
            input.add(fco);
        }

        // text + image + 延迟图片 → user message item
        List<JsonNode> allParts = new ArrayList<>();
        allParts.addAll(textBlocks);
        allParts.addAll(imageBlocks);
        allParts.addAll(deferredImages);

        if (!allParts.isEmpty()) {
            ObjectNode item = JSON.createObjectNode();
            item.put("type", "message");
            item.put("role", "user");
            ArrayNode parts = JSON.createArrayNode();
            for (JsonNode p : allParts) {
                String pType = p.has("type") ? p.get("type").asText() : "text";
                if ("text".equals(pType)) {
                    ObjectNode part = JSON.createObjectNode();
                    part.put("type", "input_text");
                    part.put("text", p.has("text") ? p.get("text").asText() : "");
                    parts.add(part);
                } else if ("image".equals(pType)) {
                    String dataUri = anthropicImageToDataURI(p);
                    if (dataUri != null && !dataUri.isEmpty()) {
                        ObjectNode part = JSON.createObjectNode();
                        part.put("type", "input_image");
                        part.put("image_url", dataUri);
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
     * 转换 Anthropic assistant 消息到 Responses input items。
     * <p>
     * text 块 → message item；tool_use 块 → function_call item；
     * thinking 块被忽略（OpenAI 不接受思考块作为输入）。
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
            String blockType = block.has("type") ? block.get("type").asText() : "text";
            switch (blockType) {
                case "text" -> {
                    if (block.has("text")) {
                        if (textBuilder.length() > 0) textBuilder.append("\n\n");
                        textBuilder.append(block.get("text").asText());
                    }
                }
                case "tool_use" -> toolUses.add(block);
                case "thinking" -> { /* 忽略 */ }
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
            ObjectNode fco = JSON.createObjectNode();
            fco.put("type", "function_call");
            fco.put("call_id", tu.has("id") ? tu.get("id").asText() : "toolu_" + UUID.randomUUID());
            fco.put("name", tu.has("name") ? tu.get("name").asText() : "");
            fco.put("arguments", tu.has("input") ? tu.get("input").toString() : "{}");
            input.add(fco);
        }
    }

    /**
     * 转换 Anthropic tool 到 Responses tool。
     */
    private static JsonNode convertAnthropicToolToResponses(JsonNode tool) {
        String toolType = tool.has("type") ? tool.get("type").asText() : "";
        // web_search_ 前缀 → web_search
        if (toolType.startsWith("web_search")) {
            ObjectNode ws = JSON.createObjectNode();
            ws.put("type", "web_search");
            return ws;
        }
        // 普通 function tool
        ObjectNode func = JSON.createObjectNode();
        func.put("type", "function");
        func.put("name", tool.has("name") ? tool.get("name").asText() : "");
        if (tool.has("description")) func.put("description", tool.get("description").asText());
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
            ObjectNode obj = (ObjectNode) schema;
            if (!obj.has("properties")) {
                obj.set("properties", JSON.createObjectNode());
            }
            return obj;
        }
        return schema;
    }

    private static ObjectNode createEmptySchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", JSON.createObjectNode());
        return schema;
    }

    /**
     * 转换 Anthropic tool_choice 到 Responses tool_choice。
     */
    private static JsonNode convertAnthropicToolChoiceToResponses(JsonNode toolChoice) {
        if (toolChoice.isTextual()) return toolChoice;

        if (toolChoice.isObject() && toolChoice.has("type")) {
            String tcType = toolChoice.get("type").asText();
            return switch (tcType) {
                case "auto" -> JSON.getNodeFactory().textNode("auto");
                case "any" -> JSON.getNodeFactory().textNode("required");
                case "none" -> JSON.getNodeFactory().textNode("none");
                case "tool" -> {
                    ObjectNode obj = JSON.createObjectNode();
                    obj.put("type", "function");
                    if (toolChoice.has("name")) obj.put("name", toolChoice.get("name").asText());
                    yield obj;
                }
                default -> toolChoice; // 未知类型透传
            };
        }
        return toolChoice;
    }

    /**
     * Anthropic effort → Responses effort 映射。
     * low→low, medium→medium, high→high, max→xhigh
     */
    private static String mapAnthropicEffortToResponses(String effort) {
        return switch (effort) {
            case "low" -> "low";
            case "medium" -> "medium";
            case "high" -> "high";
            case "max" -> "xhigh";
            default -> effort;
        };
    }

    /**
     * Anthropic stop_reason → Responses status。
     * max_tokens→"incomplete", 其他→"completed"
     */
    private static String mapAnthropicStopReasonToResponsesStatus(String stopReason) {
        return switch (stopReason) {
            case "max_tokens" -> "incomplete";
            default -> "completed";
        };
    }

    /**
     * 检测是否为推理模型（gpt-5 前缀），推理模型不接受 temperature/top_p。
     */
    private static boolean isReasoningModel(String model) {
        return model != null && model.startsWith("gpt-5");
    }

    /**
     * Anthropic image source → data URI 字符串。
     */
    private static String anthropicImageToDataURI(JsonNode imageBlock) {
        if (imageBlock == null || !imageBlock.has("source")) return null;
        JsonNode source = imageBlock.get("source");
        String mediaType = source.has("media_type") ? source.get("media_type").asText() : "image/png";
        if (mediaType.isEmpty()) mediaType = "image/png";
        String data = source.has("data") ? source.get("data").asText() : "";
        if (data.isEmpty()) return null;
        return "data:" + mediaType + ";base64," + data;
    }

    // ========================
    // 通用工具方法
    // ========================

    private static void copyIfExists(JsonNode src, ObjectNode dst, String field) {
        if (src.has(field) && !src.get(field).isNull()) dst.set(field, src.get(field));
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
