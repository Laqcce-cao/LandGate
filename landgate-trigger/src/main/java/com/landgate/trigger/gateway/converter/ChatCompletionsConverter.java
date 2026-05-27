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
 * OpenAI Chat Completions API ↔ IR 转换器。
 * <p>
 * Chat Completions 的 content 是纯字符串（非内容块数组），且 tool_calls 在消息级而非 content 数组内，
 * 这使得它成为三个 Converter 中最复杂的一个。
 */
@Slf4j
@Component
public class ChatCompletionsConverter implements ProtocolConverter {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String getFormatId() {
        return "chat_completions";
    }

    // ========================
    // 请求转换
    // ========================

    /**
     * Chat Completions 请求 → IR（Anthropic Messages 格式）。
     * <p>
     * 支持全内容块类型转换：
     * <ul>
     *   <li>content 字符串 → [{type:"text", text}] 数组</li>
     *   <li>content 数组（多模态）→ 逐个块转换（text → text, image_url → image）</li>
     *   <li>assistant 消息的 tool_calls[] → content 内的 tool_use 块</li>
     *   <li>role:tool 消息 → role:user + tool_result 块</li>
     *   <li>不支持的内容块类型（如 audio）→ 降级日志记录后丢弃</li>
     * </ul>
     */
    @Override
    public JsonNode requestToIR(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            copyIfExists(src, dst, "model");
            copyIfExists(src, dst, "stream");
            copyIfExists(src, dst, "max_tokens");
            copyIfExists(src, dst, "temperature");
            copyIfExists(src, dst, "top_p");

            // stop → stop_sequences（字符串 → 数组）
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

            // messages → IR messages（含全类型内容块转换）
            ArrayNode irMessages = JSON.createArrayNode();
            if (src.has("messages") && src.get("messages").isArray()) {
                for (JsonNode msg : src.get("messages")) {
                    String role = msg.has("role") ? msg.get("role").asText() : "user";
                    JsonNode contentNode = msg.get("content");

                    // === system 消息 → 顶层 system 字段 ===
                    if ("system".equals(role)) {
                        String systemText = extractTextFromChatContent(contentNode);
                        if (systemText != null && !systemText.isEmpty()) {
                            // 合并多个 system 消息
                            String existing = dst.has("system") ? dst.get("system").asText() + "\n" : "";
                            dst.put("system", existing + systemText);
                        }
                        continue;
                    }

                    // === tool 消息 → role:user + tool_result 块 ===
                    if ("tool".equals(role)) {
                        ObjectNode irMsg = JSON.createObjectNode();
                        irMsg.put("role", "user");
                        ArrayNode blocks = JSON.createArrayNode();
                        ObjectNode toolResult = JSON.createObjectNode();
                        toolResult.put("type", "tool_result");
                        if (msg.has("tool_call_id")) {
                            toolResult.put("tool_use_id", msg.get("tool_call_id").asText());
                        }
                        // tool 消息的 content 是纯字符串
                        ArrayNode toolContent = JSON.createArrayNode();
                        ObjectNode toolText = JSON.createObjectNode();
                        toolText.put("type", "text");
                        toolText.put("text", contentNode != null ? contentNode.asText() : "");
                        toolContent.add(toolText);
                        toolResult.set("content", toolContent);
                        blocks.add(toolResult);
                        irMsg.set("content", blocks);
                        irMessages.add(irMsg);
                        continue;
                    }

                    // === user/assistant 消息 ===
                    ObjectNode irMsg = JSON.createObjectNode();
                    irMsg.put("role", role);
                    ArrayNode contentBlocks = convertChatContentToIRBlocks(contentNode, "user".equals(role));

                    // === assistant 消息的 tool_calls → 追加到 content 数组 ===
                    if ("assistant".equals(role) && msg.has("tool_calls") && msg.get("tool_calls").isArray()) {
                        for (JsonNode tc : msg.get("tool_calls")) {
                            ObjectNode toolUse = JSON.createObjectNode();
                            toolUse.put("type", "tool_use");
                            toolUse.put("id", tc.has("id") ? tc.get("id").asText() : "toolu_" + UUID.randomUUID());
                            if (tc.has("function")) {
                                JsonNode func = tc.get("function");
                                if (func.has("name")) toolUse.put("name", func.get("name").asText());
                                if (func.has("arguments")) {
                                    try {
                                        toolUse.set("input", JSON.readTree(func.get("arguments").asText()));
                                    } catch (Exception e) {
                                        toolUse.put("input", func.get("arguments").asText());
                                    }
                                }
                            }
                            contentBlocks.add(toolUse);
                        }
                    }

                    // 跳过无内容的空消息
                    if (contentBlocks.size() == 0) {
                        log.debug("Chat→IR: skipping empty message, role={}", role);
                        continue;
                    }

                    irMsg.set("content", contentBlocks);
                    irMessages.add(irMsg);
                }
            }
            dst.set("messages", irMessages);
            return dst;
        } catch (Exception e) {
            log.warn("Failed to translate ChatCompletions→IR request: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    /**
     * IR（Anthropic Messages 格式）→ Chat Completions 请求。
     * <p>
     * 支持全内容块类型反向转换：
     * <ul>
     *   <li>IR tool_use 块 → Chat assistant 消息的 tool_calls[]</li>
     *   <li>IR tool_result 块 → Chat role:tool 独立消息</li>
     *   <li>IR image 块 → Chat content 数组中的 image_url 元素</li>
     *   <li>IR thinking/redacted_thinking/document → 降级日志 + 丢弃</li>
     * </ul>
     */
    @Override
    public String requestFromIR(JsonNode ir) {
        try {
            ObjectNode dst = JSON.createObjectNode();

            copyIfExists(ir, dst, "model");
            copyIfExists(ir, dst, "stream");
            copyIfExists(ir, dst, "max_tokens");
            copyIfExists(ir, dst, "temperature");
            copyIfExists(ir, dst, "top_p");

            // stop_sequences → stop（取第一个）
            if (ir.has("stop_sequences") && ir.get("stop_sequences").isArray()
                    && ir.get("stop_sequences").size() > 0) {
                dst.put("stop", ir.get("stop_sequences").get(0).asText());
            }

            // 构建 Chat messages
            ArrayNode chatMessages = JSON.createArrayNode();

            // IR system → Chat system message
            if (ir.has("system")) {
                String systemText = extractTextFromContentBlocks(ir.get("system"));
                if (systemText != null && !systemText.isEmpty()) {
                    ObjectNode sysMsg = JSON.createObjectNode();
                    sysMsg.put("role", "system");
                    sysMsg.put("content", systemText);
                    chatMessages.add(sysMsg);
                }
            }

            // IR messages → Chat messages（全类型内容块转换）
            if (ir.has("messages") && ir.get("messages").isArray()) {
                for (JsonNode msg : ir.get("messages")) {
                    String role = msg.has("role") ? msg.get("role").asText() : "user";
                    JsonNode contentNode = msg.get("content");

                    if (contentNode == null || !contentNode.isArray()) continue;

                    // 分类处理 content 块
                    List<JsonNode> textBlocks = new ArrayList<>();
                    List<JsonNode> imageBlocks = new ArrayList<>();
                    List<JsonNode> toolUseBlocks = new ArrayList<>();
                    List<JsonNode> toolResultBlocks = new ArrayList<>();

                    for (JsonNode block : contentNode) {
                        String blockType = block.has("type") ? block.get("type").asText() : "text";
                        switch (blockType) {
                            case "text" -> textBlocks.add(block);
                            case "image" -> imageBlocks.add(block);
                            case "tool_use" -> toolUseBlocks.add(block);
                            case "tool_result" -> toolResultBlocks.add(block);
                            case "thinking", "redacted_thinking", "document" ->
                                log.debug("IR→Chat: '{}' block downgraded, dropping", blockType);
                            default ->
                                log.debug("IR→Chat: unknown block type '{}' downgraded, dropping", blockType);
                        }
                    }

                    // === tool_result 块 → 独立的 role:tool 消息 ===
                    for (JsonNode tr : toolResultBlocks) {
                        ObjectNode toolMsg = JSON.createObjectNode();
                        toolMsg.put("role", "tool");
                        if (tr.has("tool_use_id")) {
                            toolMsg.put("tool_call_id", tr.get("tool_use_id").asText());
                        }
                        String toolContent = tr.has("content") ? extractTextFromContentBlocks(tr.get("content")) : "";
                        toolMsg.put("content", toolContent != null ? toolContent : "");
                        chatMessages.add(toolMsg);
                    }

                    // === user 消息：text + image 块 → content 数组格式 ===
                    if ("user".equals(role) && (!textBlocks.isEmpty() || !imageBlocks.isEmpty())) {
                        ObjectNode chatMsg = JSON.createObjectNode();
                        chatMsg.put("role", "user");

                        if (imageBlocks.isEmpty()) {
                            // 纯文本 → 字符串格式
                            chatMsg.put("content", buildTextFromBlocks(textBlocks));
                        } else {
                            // 有图片 → 数组格式（多模态）
                            ArrayNode contentParts = JSON.createArrayNode();
                            for (JsonNode tb : textBlocks) {
                                ObjectNode part = JSON.createObjectNode();
                                part.put("type", "text");
                                part.put("text", tb.has("text") ? tb.get("text").asText() : "");
                                contentParts.add(part);
                            }
                            for (JsonNode ib : imageBlocks) {
                                ObjectNode part = JSON.createObjectNode();
                                part.put("type", "image_url");
                                ObjectNode imageUrl = imageSourceToDataUrl(ib);
                                if (imageUrl != null) {
                                    part.set("image_url", imageUrl);
                                    contentParts.add(part);
                                }
                            }
                            chatMsg.set("content", contentParts);
                        }
                        chatMessages.add(chatMsg);
                    }

                    // === assistant 消息：text + tool_use 块 ===
                    if ("assistant".equals(role)) {
                        ObjectNode chatMsg = JSON.createObjectNode();
                        chatMsg.put("role", "assistant");

                        if (toolUseBlocks.isEmpty()) {
                            // 纯文本
                            chatMsg.put("content", buildTextFromBlocks(textBlocks));
                        } else {
                            // 有 tool_calls → content 仅文本 + tool_calls 在消息级
                            chatMsg.put("content", buildTextFromBlocks(textBlocks));
                            ArrayNode toolCalls = JSON.createArrayNode();
                            for (JsonNode tu : toolUseBlocks) {
                                ObjectNode tc = JSON.createObjectNode();
                                tc.put("id", tu.has("id") ? tu.get("id").asText() : "call_" + UUID.randomUUID());
                                tc.put("type", "function");
                                ObjectNode func = JSON.createObjectNode();
                                func.put("name", tu.has("name") ? tu.get("name").asText() : "");
                                func.put("arguments", tu.has("input") ? tu.get("input").toString() : "{}");
                                tc.set("function", func);
                                toolCalls.add(tc);
                            }
                            chatMsg.set("tool_calls", toolCalls);
                        }
                        chatMessages.add(chatMsg);
                    }
                }
            }
            dst.set("messages", chatMessages);
            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Failed to translate IR→ChatCompletions request: {}", e.getMessage());
            return "{}";
        }
    }

    // ========================
    // 非流式响应转换
    // ========================

    /**
     * Chat Completions 响应 → IR。
     * <p>
     * 支持 tool_calls 转换：Chat 的 {@code message.tool_calls[]} → IR 的 content 内 tool_use 块。
     */
    @Override
    public JsonNode responseToIR(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            dst.put("id", src.has("id") ? src.get("id").asText() : "msg_" + UUID.randomUUID());
            dst.put("type", "message");
            dst.put("role", "assistant");
            dst.put("model", src.has("model") ? src.get("model").asText() : "unknown");

            // choices[0] → stop_reason + content
            String stopReason = "end_turn";
            ArrayNode content = JSON.createArrayNode();

            if (src.has("choices") && src.get("choices").isArray() && src.get("choices").size() > 0) {
                JsonNode choice = src.get("choices").get(0);
                if (choice.has("finish_reason")) {
                    stopReason = mapFinishReason(choice.get("finish_reason").asText());
                }
                if (choice.has("message")) {
                    JsonNode message = choice.get("message");
                    // content → text 块
                    if (message.has("content") && !message.get("content").isNull()) {
                        ObjectNode textBlock = JSON.createObjectNode();
                        textBlock.put("type", "text");
                        textBlock.put("text", message.get("content").asText());
                        content.add(textBlock);
                    }
                    // tool_calls → tool_use 块
                    if (message.has("tool_calls") && message.get("tool_calls").isArray()) {
                        for (JsonNode tc : message.get("tool_calls")) {
                            ObjectNode toolUse = JSON.createObjectNode();
                            toolUse.put("type", "tool_use");
                            toolUse.put("id", tc.has("id") ? tc.get("id").asText() : "toolu_" + UUID.randomUUID());
                            if (tc.has("function")) {
                                JsonNode func = tc.get("function");
                                if (func.has("name")) toolUse.put("name", func.get("name").asText());
                                if (func.has("arguments")) {
                                    try {
                                        toolUse.set("input", JSON.readTree(func.get("arguments").asText()));
                                    } catch (Exception e) {
                                        toolUse.put("input", func.get("arguments").asText());
                                    }
                                }
                            }
                            content.add(toolUse);
                        }
                    }
                }
            }
            dst.put("stop_reason", stopReason);
            dst.set("content", content);

            // usage: prompt_tokens → input_tokens, completion_tokens → output_tokens
            if (src.has("usage")) {
                JsonNode usage = src.get("usage");
                ObjectNode irUsage = JSON.createObjectNode();
                if (usage.has("prompt_tokens")) irUsage.put("input_tokens", usage.get("prompt_tokens").asInt());
                if (usage.has("completion_tokens")) irUsage.put("output_tokens", usage.get("completion_tokens").asInt());
                if (usage.has("total_tokens")) irUsage.put("total_tokens", usage.get("total_tokens").asInt());
                dst.set("usage", irUsage);
            }

            return dst;
        } catch (Exception e) {
            log.warn("Failed to translate ChatCompletions→IR response: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    /**
     * IR → Chat Completions 响应。
     * <p>
     * 支持全内容块反向转换：tool_use 块 → tool_calls[]，image 块 → 降级丢弃。
     */
    @Override
    public String responseFromIR(JsonNode ir) {
        try {
            ObjectNode dst = JSON.createObjectNode();

            dst.put("id", ir.has("id") ? ir.get("id").asText() : "chatcmpl-" + UUID.randomUUID());
            dst.put("object", "chat.completion");
            dst.put("model", ir.has("model") ? ir.get("model").asText() : "unknown");
            dst.put("created", System.currentTimeMillis() / 1000);

            String finishReason = "stop";
            if (ir.has("stop_reason")) {
                finishReason = mapStopReasonToFinishReason(ir.get("stop_reason").asText());
            }

            // 分析 content 数组中的块类型
            String messageContent = "";
            ArrayNode toolCalls = JSON.createArrayNode();
            boolean hasToolCalls = false;

            if (ir.has("content") && ir.get("content").isArray()) {
                StringBuilder textBuilder = new StringBuilder();
                for (JsonNode block : ir.get("content")) {
                    String blockType = block.has("type") ? block.get("type").asText() : "text";
                    switch (blockType) {
                        case "text" -> {
                            if (block.has("text")) {
                                if (textBuilder.length() > 0) textBuilder.append("\n");
                                textBuilder.append(block.get("text").asText());
                            }
                        }
                        case "tool_use" -> {
                            hasToolCalls = true;
                            ObjectNode tc = JSON.createObjectNode();
                            tc.put("id", block.has("id") ? block.get("id").asText() : "call_" + UUID.randomUUID());
                            tc.put("type", "function");
                            ObjectNode func = JSON.createObjectNode();
                            func.put("name", block.has("name") ? block.get("name").asText() : "");
                            func.put("arguments", block.has("input") ? block.get("input").toString() : "{}");
                            tc.set("function", func);
                            toolCalls.add(tc);
                        }
                        case "image" -> log.debug("IR→Chat: image block in response downgraded, dropping");
                        case "thinking" -> log.debug("IR→Chat: thinking block in response downgraded, dropping");
                        default -> log.debug("IR→Chat: unknown block '{}' in response downgraded, dropping", blockType);
                    }
                }
                messageContent = textBuilder.toString();
            }

            ArrayNode choices = JSON.createArrayNode();
            ObjectNode choice = JSON.createObjectNode();
            choice.put("index", 0);
            ObjectNode message = JSON.createObjectNode();
            message.put("role", "assistant");
            message.put("content", messageContent);
            if (hasToolCalls) {
                message.set("tool_calls", toolCalls);
            }
            choice.set("message", message);
            choice.put("finish_reason", finishReason);
            choices.add(choice);
            dst.set("choices", choices);

            if (ir.has("usage")) {
                JsonNode usage = ir.get("usage");
                ObjectNode chatUsage = JSON.createObjectNode();
                if (usage.has("input_tokens")) chatUsage.put("prompt_tokens", usage.get("input_tokens").asInt());
                if (usage.has("output_tokens")) chatUsage.put("completion_tokens", usage.get("output_tokens").asInt());
                if (usage.has("total_tokens")) chatUsage.put("total_tokens", usage.get("total_tokens").asInt());
                else chatUsage.put("total_tokens",
                        (usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0)
                                + (usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0));
                dst.set("usage", chatUsage);
            }

            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Failed to translate IR→ChatCompletions response: {}", e.getMessage());
            return "{}";
        }
    }

    // ========================
    // 流式 SSE 翻译
    // ========================

    /**
     * Chat Completions SSE → IR SSE（Anthropic SSE 格式）。
     */
    @Override
    public StreamTranslator createStreamToIR(String model) {
        return new ChatToIRStreamTranslator(model);
    }

    /**
     * IR SSE → Chat Completions SSE。
     */
    @Override
    public StreamTranslator createStreamFromIR(String model) {
        return new IRToChatStreamTranslator(model);
    }

    // ========================
    // 流式翻译器内部类
    // ========================

    /**
     * Chat Completions SSE → IR SSE（即 Anthropic SSE 事件格式）。
     */
    static class ChatToIRStreamTranslator implements StreamTranslator {

        private enum State { INIT, BLOCK_STARTED, STREAMING, DONE }

        private State state = State.INIT;
        private final String model;
        private final String messageId;
        private int completionTokens = 0;
        private int inputTokens = 0;
        private String stopReason = "end_turn";

        ChatToIRStreamTranslator(String model) {
            this.model = model;
            this.messageId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }

        @Override
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (state == State.DONE || line == null || line.isBlank()) return output;

            if ("data: [DONE]".equals(line)) {
                state = State.DONE;
                return output;
            }

            if (!line.startsWith("data: ")) return output;
            String jsonLine = line.substring(6);

            try {
                JsonNode chunk = JSON.readTree(jsonLine);
                if (!chunk.has("choices") || chunk.get("choices").size() == 0) return output;
                JsonNode choice = chunk.get("choices").get(0);

                if (chunk.has("usage")) {
                    JsonNode u = chunk.get("usage");
                    if (u.has("completion_tokens")) completionTokens = u.get("completion_tokens").asInt();
                    if (u.has("prompt_tokens")) inputTokens = u.get("prompt_tokens").asInt();
                }

                boolean hasFinish = choice.has("finish_reason")
                        && !choice.get("finish_reason").isNull()
                        && !"null".equals(choice.get("finish_reason").asText());

                JsonNode delta = choice.has("delta") ? choice.get("delta") : null;
                if (delta == null && !hasFinish) return output;

                switch (state) {
                    case INIT -> {
                        if (delta != null && delta.has("role")) {
                            output.add("event: message_start");
                            output.add("data: " + formatMessageStart());
                            output.add("");
                            output.add("event: content_block_start");
                            output.add("data: " + formatContentBlockStart(0));
                            output.add("");
                            state = State.BLOCK_STARTED;
                        }
                        if (hasFinish) finish(output);
                    }
                    case BLOCK_STARTED -> {
                        if (delta != null && delta.has("content")) {
                            String text = delta.get("content").asText();
                            if (!text.isEmpty()) {
                                output.add("event: content_block_delta");
                                output.add("data: " + formatContentBlockDelta(0, text));
                                output.add("");
                                state = State.STREAMING;
                            }
                        }
                    }
                    case STREAMING -> {
                        if (delta != null && delta.has("content")) {
                            String text = delta.get("content").asText();
                            if (!text.isEmpty()) {
                                output.add("event: content_block_delta");
                                output.add("data: " + formatContentBlockDelta(0, text));
                                output.add("");
                            }
                        }
                    }
                }

                if (hasFinish) {
                    if (choice.has("finish_reason")) {
                        stopReason = mapFinishReason(choice.get("finish_reason").asText());
                    }
                    finish(output);
                }
            } catch (Exception e) {
                log.debug("Chat→IR SSE error: {}", e.getMessage());
            }
            return output;
        }

        private void finish(List<String> output) {
            if (state == State.DONE) return;
            state = State.DONE;
            output.add("event: content_block_stop");
            output.add("data: " + formatContentBlockStop(0));
            output.add("");
            output.add("event: message_delta");
            output.add("data: " + formatMessageDelta());
            output.add("");
            output.add("event: message_stop");
            output.add("data: " + formatMessageStop());
            output.add("");
        }

        @Override public boolean isDone() { return state == State.DONE; }
        @Override public int getInputTokens() { return inputTokens; }
        @Override public int getOutputTokens() { return completionTokens; }

        private String formatMessageStart() {
            return String.format("{\"type\":\"message_start\",\"message\":{\"id\":\"%s\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"%s\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}",
                    messageId, model);
        }
        private String formatContentBlockStart(int index) {
            return String.format("{\"type\":\"content_block_start\",\"index\":%d,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}", index);
        }
        private String formatContentBlockDelta(int index, String text) {
            return String.format("{\"type\":\"content_block_delta\",\"index\":%d,\"delta\":{\"type\":\"text_delta\",\"text\":\"%s\"}}",
                    index, escapeJsonValue(text));
        }
        private String formatContentBlockStop(int index) {
            return String.format("{\"type\":\"content_block_stop\",\"index\":%d}", index);
        }
        private String formatMessageDelta() {
            return String.format("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"%s\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":%d}}",
                    stopReason, completionTokens);
        }
        private String formatMessageStop() {
            return "{\"type\":\"message_stop\"}";
        }
    }

    /**
     * IR SSE（Anthropic SSE 格式）→ Chat Completions SSE。
     */
    static class IRToChatStreamTranslator implements StreamTranslator {

        private enum State { INIT, STREAMING, DONE }

        private State state = State.INIT;
        private final String completionId;
        private final String model;
        private final long created;
        private int inputTokens = 0;
        private int outputTokens = 0;
        private String stopReason = "stop";

        IRToChatStreamTranslator(String model) {
            this.model = model != null ? model : "unknown";
            this.completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            this.created = System.currentTimeMillis() / 1000;
        }

        @Override
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (state == State.DONE || line == null || line.isBlank()) return output;

            if (line.startsWith("event: ")) return output;
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
                    }
                    case "content_block_start" -> {
                        if (state == State.INIT) {
                            output.add("data: " + formatChunk(null, "assistant", null));
                            state = State.STREAMING;
                        }
                    }
                    case "content_block_delta" -> {
                        if (root.has("delta") && root.get("delta").has("text")) {
                            String text = root.get("delta").get("text").asText();
                            if (!text.isEmpty()) {
                                output.add("data: " + formatChunk(null, null, text));
                            }
                        }
                    }
                    case "message_delta" -> {
                        if (root.has("delta") && root.get("delta").has("stop_reason")) {
                            stopReason = mapStopReasonToFinishReason(root.get("delta").get("stop_reason").asText());
                        }
                        if (root.has("usage") && root.get("usage").has("output_tokens")) {
                            outputTokens = root.get("usage").get("output_tokens").asInt();
                        }
                    }
                    case "message_stop" -> {
                        output.add("data: " + formatFinalChunk());
                        output.add("data: [DONE]");
                        state = State.DONE;
                    }
                }
            } catch (Exception e) {
                log.debug("IR→Chat SSE error: {}", e.getMessage());
            }
            return output;
        }

        @Override public boolean isDone() { return state == State.DONE; }
        @Override public int getInputTokens() { return inputTokens; }
        @Override public int getOutputTokens() { return outputTokens; }

        private String formatChunk(String finishReasonParam, String role, String content) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"id\":\"").append(completionId).append("\"");
            sb.append(",\"object\":\"chat.completion.chunk\"");
            sb.append(",\"created\":").append(created);
            sb.append(",\"model\":\"").append(escapeJsonValue(model)).append("\"");
            sb.append(",\"choices\":[{\"index\":0,\"delta\":{");
            boolean hasDelta = false;
            if (role != null) { sb.append("\"role\":\"").append(escapeJsonValue(role)).append("\""); hasDelta = true; }
            if (content != null) { if (hasDelta) sb.append(","); sb.append("\"content\":\"").append(escapeJsonValue(content)).append("\""); hasDelta = true; }
            sb.append("},\"finish_reason\":");
            sb.append(finishReasonParam != null ? "\"" + finishReasonParam + "\"" : "null");
            sb.append("}]}");
            return sb.toString();
        }

        private String formatFinalChunk() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"id\":\"").append(completionId).append("\"");
            sb.append(",\"object\":\"chat.completion.chunk\"");
            sb.append(",\"created\":").append(created);
            sb.append(",\"model\":\"").append(escapeJsonValue(model)).append("\"");
            sb.append(",\"choices\":[{\"index\":0,\"delta\":{}");
            sb.append(",\"finish_reason\":\"").append(stopReason).append("\"}]");
            sb.append(",\"usage\":{\"prompt_tokens\":").append(inputTokens);
            sb.append(",\"completion_tokens\":").append(outputTokens);
            sb.append(",\"total_tokens\":").append(inputTokens + outputTokens);
            sb.append("}}");
            return sb.toString();
        }
    }

    // ========================
    // 工具方法
    // ========================

    /**
     * 从 IR 内容块数组中提取纯文本（仅拼接 text 类型块）。
     */
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

    /**
     * 从 Chat Completions 的 content 字段提取纯文本（支持字符串和数组格式）。
     * <p>
     * Chat API 的 content 可以是：
     * <ul>
     *   <li>{@code "content": "plain string"} — 纯文本</li>
     *   <li>{@code "content": [{"type":"text", "text":"..."}, {"type":"image_url", ...}]} — 多模态数组</li>
     * </ul>
     */
    private static String extractTextFromChatContent(JsonNode content) {
        if (content == null) return null;
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            return extractTextFromContentBlocks(content);
        }
        return content.asText();
    }

    /**
     * 将 Chat Completions 的 content 字段转换为 IR 内容块数组。
     * <p>
     * 支持：
     * <ul>
     *   <li>纯字符串 → {@code [{type:"text", text:"..."}]}</li>
     *   <li>数组 [{type:"text"}, {type:"image_url"}] → [{type:"text"}, {type:"image"}]</li>
     *   <li>不支持的块类型（如 input_audio）→ 降级日志 + 丢弃</li>
     * </ul>
     *
     * @param content    Chat 消息的 content 字段
     * @param isUserMsg  是否为 user 角色（user 可包含 image，assistant 不可）
     */
    private ArrayNode convertChatContentToIRBlocks(JsonNode content, boolean isUserMsg) {
        ArrayNode blocks = JSON.createArrayNode();

        if (content == null) {
            return blocks;
        }

        // 纯字符串 → [{type:"text", text}]
        if (content.isTextual()) {
            ObjectNode textBlock = JSON.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", content.asText());
            blocks.add(textBlock);
            return blocks;
        }

        // 数组格式（多模态）
        if (content.isArray()) {
            for (JsonNode part : content) {
                String partType = part.has("type") ? part.get("type").asText() : "text";
                switch (partType) {
                    case "text" -> {
                        ObjectNode textBlock = JSON.createObjectNode();
                        textBlock.put("type", "text");
                        textBlock.put("text", part.has("text") ? part.get("text").asText() : "");
                        blocks.add(textBlock);
                    }
                    case "image_url" -> {
                        if (!isUserMsg) {
                            log.debug("Chat→IR: image_url in non-user message downgraded, dropping");
                            continue;
                        }
                        JsonNode imageUrl = part.get("image_url");
                        if (imageUrl != null && imageUrl.has("url")) {
                            String url = imageUrl.get("url").asText();
                            ObjectNode imageBlock = JSON.createObjectNode();
                            imageBlock.put("type", "image");
                            // 处理 data: URL（base64 内联图片）
                            if (url.startsWith("data:")) {
                                ObjectNode source = dataUrlToImageSource(url);
                                if (source != null) {
                                    imageBlock.set("source", source);
                                    blocks.add(imageBlock);
                                } else {
                                    log.debug("Chat→IR: unsupported data URL format, dropping image");
                                }
                            } else {
                                // HTTP URL → 无法直接转为 Anthropic base64 格式，降级
                                log.debug("Chat→IR: HTTP image URL downgraded, dropping (cannot convert to base64 inline): {}", url);
                            }
                        }
                    }
                    default -> {
                        log.debug("Chat→IR: unsupported content part type '{}' downgraded, dropping", partType);
                    }
                }
            }
            return blocks;
        }

        // fallback
        ObjectNode textBlock = JSON.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", content.asText());
        blocks.add(textBlock);
        return blocks;
    }

    /**
     * 将 data: URL 转换为 IR 的 image source 对象。
     * <p>
     * 格式：{@code data:image/png;base64,<data>} → {@code {type:"base64", media_type:"image/png", data:"<data>"}}
     */
    private static ObjectNode dataUrlToImageSource(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) return null;
        try {
            int commaIdx = dataUrl.indexOf(',');
            if (commaIdx < 0) return null;
            String header = dataUrl.substring(5, commaIdx);  // "image/png;base64"
            String data = dataUrl.substring(commaIdx + 1);

            String mediaType = "image/png";
            if (header.contains(";")) {
                String[] parts = header.split(";");
                mediaType = parts[0];
            } else {
                mediaType = header;
            }

            ObjectNode source = JSON.createObjectNode();
            source.put("type", "base64");
            source.put("media_type", mediaType);
            source.put("data", data);
            return source;
        } catch (Exception e) {
            return null;
        }
    }

    /** OpenAI finish_reason → Anthropic stop_reason */
    private static String mapFinishReason(String reason) {
        return switch (reason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls" -> "tool_use";
            case "content_filter" -> "content_filtered";
            default -> reason;
        };
    }

    /** Anthropic stop_reason → OpenAI finish_reason */
    private static String mapStopReasonToFinishReason(String reason) {
        return switch (reason) {
            case "end_turn" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            case "content_filtered" -> "content_filter";
            default -> reason;
        };
    }

    /** 将多个 text 块合并为一个纯文本字符串 */
    private static String buildTextFromBlocks(List<JsonNode> textBlocks) {
        if (textBlocks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : textBlocks) {
            String text = block.has("text") ? block.get("text").asText() : "";
            if (!text.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(text);
            }
        }
        return sb.toString();
    }

    /**
     * 将 IR 的 image source 对象转换为 Chat 的 image_url 对象。
     * <p>
     * IR 格式：{@code {"source":{"type":"base64","media_type":"image/png","data":"..."}}}<br>
     * Chat 格式：{@code {"image_url":{"url":"data:image/png;base64,..."}}}
     */
    private static ObjectNode imageSourceToDataUrl(JsonNode imageBlock) {
        if (imageBlock == null || !imageBlock.has("source")) return null;
        JsonNode source = imageBlock.get("source");
        String mediaType = source.has("media_type") ? source.get("media_type").asText() : "image/png";
        String data = source.has("data") ? source.get("data").asText() : "";
        if (data.isEmpty()) return null;

        ObjectNode imageUrl = JSON.createObjectNode();
        imageUrl.put("url", "data:" + mediaType + ";base64," + data);
        return imageUrl;
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
