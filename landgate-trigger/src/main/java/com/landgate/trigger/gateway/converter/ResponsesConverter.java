package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * OpenAI Responses API ↔ IR 转换器。
 * <p>
 * 关键字段映射：
 * {@code input ↔ messages}、{@code instructions ↔ system}、
 * {@code max_output_tokens ↔ max_tokens}、{@code output ↔ content}。
 */
@Slf4j
@Component
public class ResponsesConverter implements ProtocolConverter {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String getFormatId() {
        return "responses";
    }

    // ========================
    // 请求转换
    // ========================

    /**
     * Responses API 请求 → IR（Anthropic Messages 格式）。
     * <p>
     * 支持全内容块类型转换：
     * <ul>
     *   <li>{@code input_text} / {@code output_text} → IR text 块</li>
     *   <li>{@code input_image} → IR image 块</li>
     *   <li>{@code function_call} → IR tool_use 块</li>
     *   <li>{@code function_call_output} → IR tool_result 块（role:user）</li>
     *   <li>不支持的类型 → 降级日志 + 丢弃</li>
     * </ul>
     */
    @Override
    public JsonNode requestToIR(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            copyIfExists(src, dst, "model");
            copyIfExists(src, dst, "temperature");
            copyIfExists(src, dst, "top_p");

            // max_output_tokens → max_tokens
            if (src.has("max_output_tokens")) {
                dst.put("max_tokens", src.get("max_output_tokens").asInt());
            }

            // stop → stop_sequences
            if (src.has("stop")) {
                JsonNode stop = src.get("stop");
                ArrayNode stopSeq = JSON.createArrayNode();
                if (stop.isArray()) {
                    for (JsonNode s : stop) stopSeq.add(s.asText());
                } else {
                    stopSeq.add(stop.asText());
                }
                dst.set("stop_sequences", stopSeq);
            }

            // input → messages + system
            ArrayNode irMessages = JSON.createArrayNode();
            StringBuilder systemText = new StringBuilder();

            if (src.has("instructions") && !src.get("instructions").isNull()) {
                systemText.append(src.get("instructions").asText());
            }

            if (src.has("input") && src.get("input").isArray()) {
                for (JsonNode item : src.get("input")) {
                    String role = item.has("role") ? item.get("role").asText() : "user";

                    if ("system".equals(role)) {
                        String sysContent = extractTextOrStringContent(item);
                        if (sysContent != null && !sysContent.isEmpty()) {
                            if (systemText.length() > 0) systemText.append("\n");
                            systemText.append(sysContent);
                        }
                        continue;
                    }

                    // 处理各类型 content 块
                    ArrayNode contentBlocks = convertResponsesContentToIRBlocks(item);
                    if (contentBlocks.size() == 0) continue;

                    // tool_result 使用 role:user
                    boolean hasToolResult = false;
                    for (JsonNode block : contentBlocks) {
                        if ("tool_result".equals(block.get("type").asText())) {
                            hasToolResult = true;
                            break;
                        }
                    }

                    ObjectNode irMsg = JSON.createObjectNode();
                    irMsg.put("role", hasToolResult ? "user" : role);
                    irMsg.set("content", contentBlocks);
                    irMessages.add(irMsg);
                }
            }

            if (systemText.length() > 0) {
                dst.put("system", systemText.toString());
            }
            dst.set("messages", irMessages);
            return dst;
        } catch (Exception e) {
            log.warn("Failed to translate Responses→IR request: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    /**
     * IR → Responses API 请求。
     */
    @Override
    public String requestFromIR(JsonNode ir) {
        try {
            ObjectNode dst = JSON.createObjectNode();

            copyIfExists(ir, dst, "model");
            copyIfExists(ir, dst, "temperature");
            copyIfExists(ir, dst, "top_p");

            // max_tokens → max_output_tokens
            if (ir.has("max_tokens")) {
                dst.put("max_output_tokens", ir.get("max_tokens").asInt());
            }

            // stop_sequences → stop
            if (ir.has("stop_sequences") && ir.get("stop_sequences").isArray()
                    && ir.get("stop_sequences").size() > 0) {
                dst.put("stop", ir.get("stop_sequences").get(0).asText());
            }

            // system → instructions
            if (ir.has("system")) {
                String sysText;
                if (ir.get("system").isArray()) {
                    sysText = extractTextFromContentBlocks(ir.get("system"));
                } else {
                    sysText = ir.get("system").asText();
                }
                if (sysText != null && !sysText.isEmpty()) {
                    dst.put("instructions", sysText);
                }
            }

            // messages → input
            ArrayNode input = JSON.createArrayNode();
            if (ir.has("messages") && ir.get("messages").isArray()) {
                for (JsonNode msg : ir.get("messages")) {
                    String role = msg.has("role") ? msg.get("role").asText() : "user";
                    String content = extractTextFromContentBlocks(msg.get("content"));
                    if (content == null || content.isEmpty()) continue;

                    ObjectNode item = JSON.createObjectNode();
                    item.put("role", role);
                    item.put("content", content);
                    input.add(item);
                }
            }
            dst.set("input", input);
            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Failed to translate IR→Responses request: {}", e.getMessage());
            return "{}";
        }
    }

    // ========================
    // 非流式响应转换
    // ========================

    /**
     * Responses API 响应 → IR。
     */
    @Override
    public JsonNode responseToIR(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            dst.put("id", src.has("id") ? src.get("id").asText() : "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            dst.put("type", "message");
            dst.put("role", "assistant");
            dst.put("model", src.has("model") ? src.get("model").asText() : "unknown");

            String stopReason = "end_turn";
            ArrayNode content = JSON.createArrayNode();
            if (src.has("output") && src.get("output").isArray() && src.get("output").size() > 0) {
                JsonNode outputItem = src.get("output").get(0);
                if (outputItem.has("status")) {
                    stopReason = switch (outputItem.get("status").asText()) {
                        case "completed" -> "end_turn";
                        case "incomplete" -> "max_tokens";
                        default -> outputItem.get("status").asText();
                    };
                }
                if (outputItem.has("content") && outputItem.get("content").isArray()) {
                    for (JsonNode block : outputItem.get("content")) {
                        String blockType = block.has("type") ? block.get("type").asText() : "output_text";
                        if ("output_text".equals(blockType) && block.has("text")) {
                            ObjectNode textBlock = JSON.createObjectNode();
                            textBlock.put("type", "text");
                            textBlock.put("text", block.get("text").asText());
                            content.add(textBlock);
                        }
                    }
                }
            }
            dst.put("stop_reason", stopReason);
            dst.set("content", content);

            if (src.has("usage")) {
                JsonNode usage = src.get("usage");
                ObjectNode irUsage = JSON.createObjectNode();
                if (usage.has("input_tokens")) irUsage.put("input_tokens", usage.get("input_tokens").asInt());
                if (usage.has("output_tokens")) irUsage.put("output_tokens", usage.get("output_tokens").asInt());
                if (usage.has("total_tokens")) irUsage.put("total_tokens", usage.get("total_tokens").asInt());
                else irUsage.put("total_tokens",
                        (usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0)
                                + (usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0));
                dst.set("usage", irUsage);
            }

            return dst;
        } catch (Exception e) {
            log.warn("Failed to translate Responses→IR response: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    /**
     * IR → Responses API 响应。
     */
    @Override
    public String responseFromIR(JsonNode ir) {
        try {
            ObjectNode dst = JSON.createObjectNode();

            dst.put("id", ir.has("id") ? ir.get("id").asText() : "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            dst.put("object", "response");
            dst.put("model", ir.has("model") ? ir.get("model").asText() : "unknown");
            dst.put("created_at", System.currentTimeMillis() / 1000);
            dst.put("status", "completed");

            ArrayNode output = JSON.createArrayNode();
            ObjectNode outputItem = JSON.createObjectNode();
            outputItem.put("type", "message");
            outputItem.put("role", "assistant");
            outputItem.put("id", "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));

            if (ir.has("stop_reason")) {
                outputItem.put("status", ir.get("stop_reason").asText());
            }

            ArrayNode content = JSON.createArrayNode();
            if (ir.has("content") && ir.get("content").isArray()) {
                for (JsonNode block : ir.get("content")) {
                    String blockType = block.has("type") ? block.get("type").asText() : "text";
                    if ("text".equals(blockType) && block.has("text")) {
                        ObjectNode textBlock = JSON.createObjectNode();
                        textBlock.put("type", "output_text");
                        textBlock.put("text", block.get("text").asText());
                        content.add(textBlock);
                    }
                }
            }
            outputItem.set("content", content);
            output.add(outputItem);
            dst.set("output", output);

            if (ir.has("usage")) {
                JsonNode usage = ir.get("usage");
                ObjectNode respUsage = JSON.createObjectNode();
                if (usage.has("input_tokens")) respUsage.put("input_tokens", usage.get("input_tokens").asInt());
                if (usage.has("output_tokens")) respUsage.put("output_tokens", usage.get("output_tokens").asInt());
                if (usage.has("total_tokens")) respUsage.put("total_tokens", usage.get("total_tokens").asInt());
                else respUsage.put("total_tokens",
                        (usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0)
                                + (usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0));
                dst.set("usage", respUsage);
            }

            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Failed to translate IR→Responses response: {}", e.getMessage());
            return "{}";
        }
    }

    // ========================
    // 流式 SSE 翻译
    // ========================

    /**
     * Responses API SSE → IR SSE（Anthropic SSE 事件格式）。
     */
    @Override
    public StreamTranslator createStreamToIR(String model) {
        return new ResponsesToIRStreamTranslator();
    }

    /**
     * IR SSE → Responses API SSE。
     */
    @Override
    public StreamTranslator createStreamFromIR(String model) {
        return new IRToResponsesStreamTranslator(model);
    }

    // ========================
    // 流式翻译器内部类
    // ========================

    /**
     * Responses SSE → IR SSE。
     */
    static class ResponsesToIRStreamTranslator implements StreamTranslator {

        private enum State { INIT, BLOCK_STARTED, STREAMING, DONE }

        private State state = State.INIT;
        private final String messageId;
        private String model = "unknown";
        private int inputTokens = 0;
        private int outputTokens = 0;
        private String stopReason = "end_turn";

        ResponsesToIRStreamTranslator() {
            this.messageId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }

        @Override
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (state == State.DONE || line == null || line.isBlank()) return output;
            if (!line.startsWith("data: ")) return output;

            String json = line.substring(6);
            try {
                JsonNode root = JSON.readTree(json);
                String type = root.has("type") ? root.get("type").asText() : null;
                if (type == null) return output;

                switch (type) {
                    case "response.created" -> {
                        if (root.has("response") && root.get("response").has("model")) {
                            model = root.get("response").get("model").asText();
                        }
                        output.add("event: message_start");
                        output.add("data: " + formatMessageStart());
                        output.add("");
                        output.add("event: content_block_start");
                        output.add("data: " + formatContentBlockStart(0));
                        output.add("");
                        state = State.BLOCK_STARTED;
                    }
                    case "response.output_text.delta" -> {
                        if (root.has("delta")) {
                            String text = root.get("delta").asText();
                            if (!text.isEmpty()) {
                                output.add("event: content_block_delta");
                                output.add("data: " + formatContentBlockDelta(0, text));
                                output.add("");
                                state = State.STREAMING;
                            }
                        }
                    }
                    case "response.completed" -> {
                        if (root.has("response")) {
                            JsonNode resp = root.get("response");
                            if (resp.has("usage")) {
                                JsonNode usage = resp.get("usage");
                                if (usage.has("input_tokens")) inputTokens = usage.get("input_tokens").asInt();
                                if (usage.has("output_tokens")) outputTokens = usage.get("output_tokens").asInt();
                            }
                            if (resp.has("output") && resp.get("output").isArray() && resp.get("output").size() > 0) {
                                JsonNode outItem = resp.get("output").get(0);
                                if (outItem.has("status")) {
                                    stopReason = switch (outItem.get("status").asText()) {
                                        case "completed" -> "end_turn";
                                        case "incomplete" -> "max_tokens";
                                        default -> outItem.get("status").asText();
                                    };
                                }
                            }
                        }
                        output.add("event: content_block_stop");
                        output.add("data: " + formatContentBlockStop(0));
                        output.add("");
                        output.add("event: message_delta");
                        output.add("data: " + formatMessageDelta());
                        output.add("");
                        output.add("event: message_stop");
                        output.add("data: " + formatMessageStop());
                        output.add("");
                        state = State.DONE;
                    }
                }
            } catch (Exception e) {
                log.debug("Responses→IR SSE error: {}", e.getMessage());
            }
            return output;
        }

        @Override public boolean isDone() { return state == State.DONE; }
        @Override public int getInputTokens() { return inputTokens; }
        @Override public int getOutputTokens() { return outputTokens; }

        private String formatMessageStart() {
            return String.format("{\"type\":\"message_start\",\"message\":{\"id\":\"%s\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"%s\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}",
                    messageId, model);
        }
        private String formatContentBlockStart(int idx) {
            return String.format("{\"type\":\"content_block_start\",\"index\":%d,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}", idx);
        }
        private String formatContentBlockDelta(int idx, String text) {
            return String.format("{\"type\":\"content_block_delta\",\"index\":%d,\"delta\":{\"type\":\"text_delta\",\"text\":\"%s\"}}", idx, escapeJsonValue(text));
        }
        private String formatContentBlockStop(int idx) {
            return String.format("{\"type\":\"content_block_stop\",\"index\":%d}", idx);
        }
        private String formatMessageDelta() {
            return String.format("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"%s\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":%d}}", stopReason, outputTokens);
        }
        private String formatMessageStop() {
            return "{\"type\":\"message_stop\"}";
        }
    }

    /**
     * IR SSE → Responses API SSE。
     */
    static class IRToResponsesStreamTranslator implements StreamTranslator {

        private enum State { INIT, STREAMING, DONE }

        private State state = State.INIT;
        private final String responseId;
        private final String itemId;
        private final String model;
        private final long createdAt;
        private long sequenceNumber = 0;
        private int inputTokens = 0;
        private int outputTokens = 0;
        private String stopReason = "end_turn";
        private String assistantContent = "";

        IRToResponsesStreamTranslator(String model) {
            this.model = model != null ? model : "unknown";
            this.responseId = "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            this.itemId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            this.createdAt = System.currentTimeMillis() / 1000;
        }

        @Override
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (state == State.DONE || line == null || line.isBlank()) return output;
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
                            if (msg.has("usage") && msg.get("usage").has("input_tokens")) {
                                inputTokens = msg.get("usage").get("input_tokens").asInt();
                            }
                        }
                        sendCreatedEvent(output);
                        sendInProgressEvent(output);
                        sendOutputItemAdded(output);
                        sendContentPartAdded(output);
                        state = State.STREAMING;
                    }
                    case "content_block_delta" -> {
                        if (root.has("delta") && root.get("delta").has("text")) {
                            String text = root.get("delta").get("text").asText();
                            if (!text.isEmpty()) {
                                assistantContent += text;
                                appendDeltaEvent(output, text);
                            }
                        }
                    }
                    case "message_delta" -> {
                        if (root.has("delta") && root.get("delta").has("stop_reason")) {
                            stopReason = root.get("delta").get("stop_reason").asText();
                        }
                        if (root.has("usage") && root.get("usage").has("output_tokens")) {
                            outputTokens = root.get("usage").get("output_tokens").asInt();
                        }
                    }
                    case "message_stop" -> {
                        sendTextDoneEvent(output);
                        sendContentPartDoneEvent(output);
                        sendOutputItemDoneEvent(output);
                        sendCompletedEvent(output);
                        state = State.DONE;
                    }
                }
            } catch (Exception e) {
                log.debug("IR→Responses SSE error: {}", e.getMessage());
            }
            return output;
        }

        @Override public boolean isDone() { return state == State.DONE; }
        @Override public int getInputTokens() { return inputTokens; }
        @Override public int getOutputTokens() { return outputTokens; }

        private void sendCreatedEvent(List<String> out) {
            out.add("event: response.created");
            out.add("data: " + fmt("{\"type\":\"response.created\",\"sequence_number\":%d,\"response\":{\"id\":\"%s\",\"object\":\"response\",\"model\":\"%s\",\"created_at\":%d,\"status\":\"in_progress\",\"output\":[],\"usage\":null}}",
                    sequenceNumber++, responseId, escapeJsonValue(model), createdAt));
            out.add("");
        }
        private void sendInProgressEvent(List<String> out) {
            out.add("event: response.in_progress");
            out.add("data: " + fmt("{\"type\":\"response.in_progress\",\"sequence_number\":%d,\"response\":{\"id\":\"%s\",\"object\":\"response\",\"model\":\"%s\",\"created_at\":%d,\"status\":\"in_progress\",\"output\":[],\"usage\":null}}",
                    sequenceNumber++, responseId, escapeJsonValue(model), createdAt));
            out.add("");
        }
        private void sendOutputItemAdded(List<String> out) {
            out.add("event: response.output_item.added");
            out.add("data: " + fmt("{\"type\":\"response.output_item.added\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":0,\"item\":{\"id\":\"%s\",\"type\":\"message\",\"status\":\"in_progress\",\"role\":\"assistant\",\"content\":[]}}",
                    sequenceNumber++, responseId, itemId));
            out.add("");
        }
        private void sendContentPartAdded(List<String> out) {
            out.add("event: response.content_part.added");
            out.add("data: " + fmt("{\"type\":\"response.content_part.added\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"\",\"annotations\":[]}}",
                    sequenceNumber++, responseId, itemId));
            out.add("");
        }
        private void appendDeltaEvent(List<String> out, String text) {
            out.add("event: response.output_text.delta");
            out.add("data: " + fmt("{\"type\":\"response.output_text.delta\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":0,\"content_index\":0,\"delta\":\"%s\"}",
                    sequenceNumber++, responseId, itemId, escapeJsonValue(text)));
            out.add("");
        }
        private void sendTextDoneEvent(List<String> out) {
            out.add("event: response.text.done");
            out.add("data: " + fmt("{\"type\":\"response.text.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":0,\"content_index\":0,\"text\":\"%s\"}",
                    sequenceNumber++, responseId, itemId, escapeJsonValue(assistantContent)));
            out.add("");
        }
        private void sendContentPartDoneEvent(List<String> out) {
            out.add("event: response.content_part.done");
            out.add("data: " + fmt("{\"type\":\"response.content_part.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"%s\"}}",
                    sequenceNumber++, responseId, itemId, escapeJsonValue(assistantContent)));
            out.add("");
        }
        private void sendOutputItemDoneEvent(List<String> out) {
            String status = mapStopReasonToResponsesStatus(stopReason);
            out.add("event: response.output_item.done");
            out.add("data: " + fmt("{\"type\":\"response.output_item.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":0,\"item\":{\"id\":\"%s\",\"type\":\"message\",\"status\":\"%s\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"%s\"}]}}",
                    sequenceNumber++, responseId, itemId, status, escapeJsonValue(assistantContent)));
            out.add("");
        }
        private void sendCompletedEvent(List<String> out) {
            String status = mapStopReasonToResponsesStatus(stopReason);
            out.add("event: response.completed");
            out.add("data: " + fmt("{\"type\":\"response.completed\",\"sequence_number\":%d,\"response\":{\"id\":\"%s\",\"object\":\"response\",\"model\":\"%s\",\"created_at\":%d,\"status\":\"%s\",\"output\":[{\"id\":\"%s\",\"type\":\"message\",\"role\":\"assistant\",\"status\":\"%s\",\"content\":[{\"type\":\"output_text\",\"text\":\"%s\"}]}],\"usage\":{\"input_tokens\":%d,\"output_tokens\":%d,\"total_tokens\":%d}}}",
                    sequenceNumber++, responseId, escapeJsonValue(model), createdAt, status,
                    itemId, status, escapeJsonValue(assistantContent),
                    inputTokens, outputTokens, inputTokens + outputTokens));
            out.add("");
        }

        private static String mapStopReasonToResponsesStatus(String reason) {
            return switch (reason) {
                case "end_turn" -> "completed";
                case "max_tokens" -> "incomplete";
                default -> reason;
            };
        }

        private static String fmt(String format, Object... args) { return String.format(format, args); }
    }

    // ========================
    // 工具方法
    // ========================

    /**
     * 将 Responses API 的 content 数组转换为 IR 内容块数组。
     * <p>
     * 支持：input_text/output_text → text、input_image → image、
     * function_call → tool_use、function_call_output → tool_result。
     */
    private ArrayNode convertResponsesContentToIRBlocks(JsonNode item) {
        ArrayNode blocks = JSON.createArrayNode();
        if (item == null) return blocks;

        JsonNode content = item.get("content");
        if (content == null) return blocks;

        // 纯字符串 → [{type:"text", text}]
        if (content.isTextual()) {
            ObjectNode textBlock = JSON.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", content.asText());
            blocks.add(textBlock);
            return blocks;
        }

        if (!content.isArray()) return blocks;

        for (JsonNode block : content) {
            String blockType = block.has("type") ? block.get("type").asText() : "text";
            switch (blockType) {
                case "input_text", "output_text", "text" -> {
                    ObjectNode textBlock = JSON.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", block.has("text") ? block.get("text").asText() : "");
                    blocks.add(textBlock);
                }
                case "input_image" -> {
                    if (block.has("image_url")) {
                        String url = block.get("image_url").asText();
                        ObjectNode imageBlock = JSON.createObjectNode();
                        imageBlock.put("type", "image");
                        if (url.startsWith("data:")) {
                            ObjectNode source = dataUrlToImageSource(url);
                            if (source != null) {
                                imageBlock.set("source", source);
                                blocks.add(imageBlock);
                            } else {
                                log.debug("Responses→IR: unsupported image URL format, dropping");
                            }
                        } else {
                            log.debug("Responses→IR: HTTP image URL downgraded, dropping: {}", url);
                        }
                    }
                }
                case "function_call" -> {
                    ObjectNode toolUse = JSON.createObjectNode();
                    toolUse.put("type", "tool_use");
                    toolUse.put("id", block.has("call_id") ? block.get("call_id").asText()
                            : "toolu_" + UUID.randomUUID());
                    if (block.has("name")) toolUse.put("name", block.get("name").asText());
                    if (block.has("arguments")) {
                        String args = block.get("arguments").asText();
                        try {
                            toolUse.set("input", JSON.readTree(args));
                        } catch (Exception e) {
                            toolUse.put("input", args);
                        }
                    }
                    blocks.add(toolUse);
                }
                case "function_call_output" -> {
                    ObjectNode toolResult = JSON.createObjectNode();
                    toolResult.put("type", "tool_result");
                    if (block.has("call_id")) toolResult.put("tool_use_id", block.get("call_id").asText());
                    ArrayNode resultContent = JSON.createArrayNode();
                    ObjectNode resultText = JSON.createObjectNode();
                    resultText.put("type", "text");
                    resultText.put("text", block.has("output") ? block.get("output").asText() : "");
                    resultContent.add(resultText);
                    toolResult.set("content", resultContent);
                    blocks.add(toolResult);
                }
                default -> log.debug("Responses→IR: unsupported block type '{}' downgraded, dropping", blockType);
            }
        }
        return blocks;
    }

    /**
     * 将 data: URL 转换为 IR image source 对象。
     */
    private static ObjectNode dataUrlToImageSource(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) return null;
        try {
            int commaIdx = dataUrl.indexOf(',');
            if (commaIdx < 0) return null;
            String header = dataUrl.substring(5, commaIdx);
            String data = dataUrl.substring(commaIdx + 1);
            String mediaType = header.contains(";") ? header.split(";")[0] : header;

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
     * 从 Responses API input 项提取纯文本字符串。
     * 支持 {@code "content": "string"} 和 {@code "content": [{type:"input_text", text:"..."}]} 两种格式。
     */
    private static String extractTextOrStringContent(JsonNode item) {
        if (item == null) return null;
        JsonNode content = item.get("content");
        if (content == null) return null;
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                String type = block.has("type") ? block.get("type").asText() : "";
                if ("input_text".equals(type) || "output_text".equals(type) || "text".equals(type)) {
                    if (block.has("text")) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(block.get("text").asText());
                    }
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        return content.asText();
    }

    private static String extractTextFromContentBlocks(JsonNode content) {
        if (content == null) return null;
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if (block.has("type") && "text".equals(block.get("type").asText()) && block.has("text")) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(block.get("text").asText());
            }
        }
        return sb.toString();
    }

    private static void copyIfExists(JsonNode src, ObjectNode dst, String field) {
        if (src.has(field) && !src.get(field).isNull()) dst.set(field, src.get(field));
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
}
