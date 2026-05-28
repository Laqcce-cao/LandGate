package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI Responses API（IR）→ OpenAI Chat Completions API 转换器。
 * <p>
 * 负责请求反向转换、非流式响应反向转换、流式 SSE 反向翻译。
 * 由 {@link ChatCompletionsConverter} 门面委托调用，不作为独立 Spring Bean。
 *
 * <p>参照：sub2api {@code responses_to_chatcompletions.go} + {@code chatcompletions_responses_bridge.go}
 */
@Slf4j
public class ResponsesToChatCompletionsConverter {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ========================
    // 请求反向转换：Responses IR → Chat Completions
    // ========================

    /**
     * 将 Responses API 请求（IR）转换为 Chat Completions 请求。
     */
    public String requestFromIR(JsonNode ir) {
        try {
            ObjectNode dst = JSON.createObjectNode();

            copyIfExists(ir, dst, "model");
            copyIfExists(ir, dst, "temperature");
            copyIfExists(ir, dst, "top_p");

            // max_output_tokens → max_completion_tokens
            if (ir.has("max_output_tokens")) {
                dst.put("max_completion_tokens", ir.get("max_output_tokens").asInt());
            }

            // stream
            if (ir.has("stream")) {
                boolean streamFlag = ir.get("stream").asBoolean();
                dst.put("stream", streamFlag);
                // 流式必须显式打开 include_usage，否则 OpenAI 兼容上游不会在最终 chunk 返回 usage，
                // 上层翻译器拿到 output_tokens=0，计费与日志会失真。
                if (streamFlag) {
                    ObjectNode streamOptions = JSON.createObjectNode();
                    streamOptions.put("include_usage", true);
                    dst.set("stream_options", streamOptions);
                }
            }

            // reasoning.effort → reasoning_effort
            if (ir.has("reasoning") && ir.get("reasoning").has("effort")) {
                dst.put("reasoning_effort", ir.get("reasoning").get("effort").asText());
            }

            // --- input[] → messages[] ---
            ArrayNode messages = JSON.createArrayNode();

            // instructions → system message（放在最前面）
            if (ir.has("instructions") && !ir.get("instructions").isNull()) {
                ObjectNode sysMsg = JSON.createObjectNode();
                sysMsg.put("role", "system");
                sysMsg.put("content", ir.get("instructions").asText());
                messages.add(sysMsg);
            }

            if (ir.has("input")) {
                JsonNode inputNode = ir.get("input");
                if (inputNode.isArray()) {
                    for (JsonNode item : inputNode) {
                        String itemType = item.has("type") ? item.get("type").asText() : null;
                        String role = item.has("role") ? item.get("role").asText() : null;

                        // function_call → assistant message with tool_calls
                        if ("function_call".equals(itemType)) {
                            ObjectNode msg = JSON.createObjectNode();
                            msg.put("role", "assistant");
                            msg.put("content", "");
                            ArrayNode toolCalls = JSON.createArrayNode();
                            ObjectNode tc = JSON.createObjectNode();
                            tc.put("id", item.has("call_id") ? item.get("call_id").asText() : "");
                            tc.put("type", "function");
                            ObjectNode func = JSON.createObjectNode();
                            func.put("name", item.has("name") ? item.get("name").asText() : "");
                            func.put("arguments", item.has("arguments") ? item.get("arguments").asText() : "{}");
                            tc.set("function", func);
                            toolCalls.add(tc);
                            msg.set("tool_calls", toolCalls);
                            messages.add(msg);
                            continue;
                        }

                        // function_call_output → tool message
                        if ("function_call_output".equals(itemType)) {
                            ObjectNode msg = JSON.createObjectNode();
                            msg.put("role", "tool");
                            msg.put("tool_call_id", item.has("call_id") ? item.get("call_id").asText() : "");
                            msg.put("content", item.has("output") ? item.get("output").asText() : "(empty)");
                            messages.add(msg);
                            continue;
                        }

                        // 普通 message item
                        if (role != null) {
                            String chatRole = "developer".equals(role) ? "system" : role;
                            ObjectNode msg = JSON.createObjectNode();
                            msg.put("role", chatRole);

                            JsonNode content = item.get("content");
                            if (content != null && content.isArray()) {
                                // 数组 content → Chat content
                                StringBuilder textContent = new StringBuilder();
                                ArrayNode multiParts = JSON.createArrayNode();
                                boolean hasImage = false;

                                for (JsonNode part : content) {
                                    String partType = part.has("type") ? part.get("type").asText() : "";
                                    switch (partType) {
                                        case "input_text", "output_text", "text" -> {
                                            String t = part.has("text") ? part.get("text").asText() : "";
                                            if (hasImage) {
                                                ObjectNode p = JSON.createObjectNode();
                                                p.put("type", "text");
                                                p.put("text", t);
                                                multiParts.add(p);
                                            } else {
                                                if (textContent.length() > 0) textContent.append("\n");
                                                textContent.append(t);
                                            }
                                        }
                                        case "input_image" -> {
                                            hasImage = true;
                                            // 将已有文本转为 parts
                                            if (textContent.length() > 0) {
                                                ObjectNode p = JSON.createObjectNode();
                                                p.put("type", "text");
                                                p.put("text", textContent.toString());
                                                multiParts.add(p);
                                                textContent.setLength(0);
                                            }
                                            ObjectNode p = JSON.createObjectNode();
                                            p.put("type", "image_url");
                                            ObjectNode imageUrl = JSON.createObjectNode();
                                            imageUrl.put("url", part.has("image_url")
                                                    ? part.get("image_url").asText() : "");
                                            p.set("image_url", imageUrl);
                                            multiParts.add(p);
                                        }
                                    }
                                }
                                if (hasImage) {
                                    msg.set("content", multiParts);
                                } else {
                                    msg.put("content", textContent.toString());
                                }
                            } else if (content != null && content.isTextual()) {
                                msg.put("content", content.asText());
                            } else {
                                msg.put("content", "");
                            }
                            messages.add(msg);
                        }
                    }
                }
            }

            dst.set("messages", messages);

            // --- tools ---
            if (ir.has("tools") && ir.get("tools").isArray()) {
                ArrayNode chatTools = JSON.createArrayNode();
                for (JsonNode tool : ir.get("tools")) {
                    String toolType = tool.has("type") ? tool.get("type").asText() : "";
                    if ("function".equals(toolType)) {
                        ObjectNode ct = JSON.createObjectNode();
                        ct.put("type", "function");
                        ObjectNode func = JSON.createObjectNode();
                        func.put("name", tool.has("name") ? tool.get("name").asText() : "");
                        if (tool.has("description")) func.put("description", tool.get("description").asText());
                        if (tool.has("parameters")) func.set("parameters", tool.get("parameters"));
                        ct.set("function", func);
                        chatTools.add(ct);
                    }
                    // 非 function 工具丢弃（Chat Completions 不支持）
                }
                if (chatTools.size() > 0) {
                    dst.set("tools", chatTools);
                }
            }

            // --- tool_choice ---
            if (ir.has("tool_choice")) {
                dst.set("tool_choice", convertResponsesToolChoiceToChat(ir.get("tool_choice")));
            }

            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Responses→ChatCompletions requestFromIR error: {}", e.getMessage());
            return "{}";
        }
    }

    // ========================
    // 非流式响应反向转换：Responses IR → Chat Completions
    // ========================

    /**
     * 将 Responses 非流式响应（IR）转换为 Chat Completions 响应。
     */
    public String responseFromIR(JsonNode ir) {
        try {
            ObjectNode dst = JSON.createObjectNode();

            dst.put("id", ir.has("id") ? ir.get("id").asText()
                    : "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            dst.put("object", "chat.completion");
            dst.put("created", System.currentTimeMillis() / 1000);
            dst.put("model", ir.has("model") ? ir.get("model").asText() : "unknown");

            // output[] → message content + tool_calls + reasoning_content
            String contentText = "";
            String reasoningText = "";
            ArrayNode toolCalls = JSON.createArrayNode();
            boolean hasToolCalls = false;

            if (ir.has("output") && ir.get("output").isArray()) {
                for (JsonNode item : ir.get("output")) {
                    String itemType = item.has("type") ? item.get("type").asText() : "";

                    switch (itemType) {
                        case "reasoning" -> {
                            if (item.has("summary") && item.get("summary").isArray()) {
                                StringBuilder sb = new StringBuilder();
                                for (JsonNode s : item.get("summary")) {
                                    if ("summary_text".equals(s.has("type") ? s.get("type").asText() : "")
                                            && s.has("text")) {
                                        if (sb.length() > 0) sb.append("\n");
                                        sb.append(s.get("text").asText());
                                    }
                                }
                                reasoningText = sb.toString();
                            }
                        }
                        case "message" -> {
                            if (item.has("content") && item.get("content").isArray()) {
                                StringBuilder sb = new StringBuilder();
                                for (JsonNode part : item.get("content")) {
                                    if ("output_text".equals(part.has("type") ? part.get("type").asText() : "")
                                            && part.has("text")) {
                                        if (sb.length() > 0) sb.append("\n");
                                        sb.append(part.get("text").asText());
                                    }
                                }
                                contentText = sb.toString();
                            }
                        }
                        case "function_call" -> {
                            hasToolCalls = true;
                            ObjectNode tc = JSON.createObjectNode();
                            tc.put("id", item.has("call_id") ? item.get("call_id").asText() : "");
                            tc.put("type", "function");
                            ObjectNode func = JSON.createObjectNode();
                            func.put("name", item.has("name") ? item.get("name").asText() : "");
                            func.put("arguments", item.has("arguments") ? item.get("arguments").asText() : "{}");
                            tc.set("function", func);
                            toolCalls.add(tc);
                        }
                        case "web_search_call" -> { /* 丢弃 */ }
                        default ->
                            log.debug("Responses→Chat: unknown output type '{}'", itemType);
                    }
                }
            }

            // 构建 choices[0].message
            ArrayNode choices = JSON.createArrayNode();
            ObjectNode choice = JSON.createObjectNode();
            choice.put("index", 0);
            ObjectNode message = JSON.createObjectNode();
            message.put("role", "assistant");
            message.put("content", contentText);
            if (hasToolCalls) {
                message.set("tool_calls", toolCalls);
            }
            if (!reasoningText.isEmpty()) {
                message.put("reasoning_content", reasoningText);
            }
            choice.set("message", message);

            // finish_reason
            String status = ir.has("status") ? ir.get("status").asText() : "completed";
            String incompleteReason = "";
            if (ir.has("incomplete_details") && ir.get("incomplete_details").has("reason")) {
                incompleteReason = ir.get("incomplete_details").get("reason").asText();
            }
            String finishReason = mapResponsesStatusToChatFinishReason(status, incompleteReason, hasToolCalls);
            choice.put("finish_reason", finishReason);
            choices.add(choice);
            dst.set("choices", choices);

            // usage
            if (ir.has("usage")) {
                JsonNode usage = ir.get("usage");
                ObjectNode chatUsage = JSON.createObjectNode();
                int inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
                int outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
                chatUsage.put("prompt_tokens", inputTokens);
                chatUsage.put("completion_tokens", outputTokens);
                chatUsage.put("total_tokens", inputTokens + outputTokens);
                if (usage.has("input_tokens_details")
                        && usage.get("input_tokens_details").has("cached_tokens")
                        && usage.get("input_tokens_details").get("cached_tokens").asInt() > 0) {
                    ObjectNode details = JSON.createObjectNode();
                    details.put("cached_tokens", usage.get("input_tokens_details").get("cached_tokens").asInt());
                    chatUsage.set("prompt_tokens_details", details);
                }
                dst.set("usage", chatUsage);
            }

            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Responses→ChatCompletions responseFromIR error: {}", e.getMessage());
            return "{}";
        }
    }

    // ========================
    // 流式 SSE 翻译：Responses SSE（IR）→ Chat Completions SSE
    // ========================

    /**
     * 创建 Responses SSE → Chat Completions SSE 流式翻译器。
     */
    public StreamTranslator createStreamFromIR(String model) {
        return new IRToChatStreamTranslator(model);
    }

    // ========================
    // 流式翻译器内部类
    // ========================

    /**
     * Responses SSE 事件 → Chat Completions SSE chunk。
     */
    static class IRToChatStreamTranslator implements StreamTranslator {

        private boolean done = false;
        private boolean sentRole = false;
        private boolean sawToolCall = false;
        private final String completionId;
        private String model;
        private final long created;

        private int inputTokens = 0;
        private int outputTokens = 0;
        private int cachedTokens = 0;

        private final Map<Integer, Integer> outputIndexToToolIndex = new HashMap<>();
        private int nextToolCallIndex = 0;

        IRToChatStreamTranslator(String model) {
            this.model = model != null ? model : "unknown";
            this.completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            this.created = System.currentTimeMillis() / 1000;
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
                        if (root.has("response")) {
                            JsonNode resp = root.get("response");
                            if (resp.has("model")) model = resp.get("model").asText();
                        }
                        if (!sentRole) {
                            output.add("data: " + formatDeltaChunk("assistant", null, null, null));
                            sentRole = true;
                        }
                    }
                    case "response.output_text.delta" -> {
                        String text = root.has("delta") ? root.get("delta").asText() : "";
                        if (!text.isEmpty()) {
                            output.add("data: " + formatDeltaChunk(null, text, null, null));
                        }
                    }
                    case "response.output_item.added" -> {
                        if (root.has("item") && root.get("item").has("type")
                                && "function_call".equals(root.get("item").get("type").asText())) {
                            sawToolCall = true;
                            String callId = root.get("item").has("call_id")
                                    ? root.get("item").get("call_id").asText() : "";
                            String funcName = root.get("item").has("name")
                                    ? root.get("item").get("name").asText() : "";
                            int outputIdx = root.has("output_index") ? root.get("output_index").asInt() : 0;

                            int chatIdx = nextToolCallIndex++;
                            outputIndexToToolIndex.put(outputIdx, chatIdx);

                            output.add("data: " + formatToolCallChunk(chatIdx, callId, funcName, null));
                        }
                    }
                    case "response.function_call_arguments.delta" -> {
                        String delta = root.has("delta") ? root.get("delta").asText() : "";
                        if (!delta.isEmpty()) {
                            int outputIdx = root.has("output_index") ? root.get("output_index").asInt() : 0;
                            Integer chatIdx = outputIndexToToolIndex.get(outputIdx);
                            if (chatIdx != null) {
                                output.add("data: " + formatToolCallChunk(chatIdx, null, null, delta));
                            }
                        }
                    }
                    case "response.reasoning_summary_text.delta" -> {
                        String delta = root.has("delta") ? root.get("delta").asText() : "";
                        if (!delta.isEmpty()) {
                            output.add("data: " + formatDeltaChunk(null, null, delta, null));
                        }
                    }
                    case "response.completed", "response.done", "response.incomplete", "response.failed" -> {
                        // usage
                        if (root.has("usage")) {
                            JsonNode u = root.get("usage");
                            if (u.has("input_tokens")) inputTokens = u.get("input_tokens").asInt();
                            if (u.has("output_tokens")) outputTokens = u.get("output_tokens").asInt();
                            if (u.has("input_tokens_details") && u.get("input_tokens_details").has("cached_tokens")) {
                                cachedTokens = u.get("input_tokens_details").get("cached_tokens").asInt();
                            }
                        } else if (root.has("response") && root.get("response").has("usage")) {
                            JsonNode u = root.get("response").get("usage");
                            if (u.has("input_tokens")) inputTokens = u.get("input_tokens").asInt();
                            if (u.has("output_tokens")) outputTokens = u.get("output_tokens").asInt();
                        }

                        // finish_reason
                        String finishReason;
                        if (root.has("response")) {
                            JsonNode resp = root.get("response");
                            String respStatus = resp.has("status") ? resp.get("status").asText() : "completed";
                            if ("incomplete".equals(respStatus)
                                    && resp.has("incomplete_details")
                                    && "max_output_tokens".equals(resp.get("incomplete_details").has("reason")
                                        ? resp.get("incomplete_details").get("reason").asText() : "")) {
                                finishReason = "length";
                            } else if ("completed".equals(respStatus) && sawToolCall) {
                                finishReason = "tool_calls";
                            } else {
                                finishReason = "stop";
                            }
                        } else {
                            finishReason = sawToolCall ? "tool_calls" : "stop";
                        }

                        output.add("data: " + formatFinishChunk(finishReason));
                        output.add("data: [DONE]");
                        done = true;
                    }
                }
            } catch (Exception e) {
                log.debug("IR→Chat SSE error: {}", e.getMessage());
            }
            return output;
        }

        private String formatDeltaChunk(String role, String content, String reasoningContent, String finishReason) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"id\":\"").append(completionId).append("\"");
            sb.append(",\"object\":\"chat.completion.chunk\"");
            sb.append(",\"created\":").append(created);
            sb.append(",\"model\":\"").append(escapeJsonValue(model)).append("\"");
            sb.append(",\"choices\":[{\"index\":0,\"delta\":{");
            boolean hasField = false;
            if (role != null) {
                sb.append("\"role\":\"").append(escapeJsonValue(role)).append("\"");
                hasField = true;
            }
            if (content != null) {
                if (hasField) sb.append(",");
                sb.append("\"content\":\"").append(escapeJsonValue(content)).append("\"");
                hasField = true;
            }
            if (reasoningContent != null) {
                if (hasField) sb.append(",");
                sb.append("\"reasoning_content\":\"").append(escapeJsonValue(reasoningContent)).append("\"");
                hasField = true;
            }
            sb.append("},\"finish_reason\":");
            sb.append(finishReason != null ? "\"" + finishReason + "\"" : "null");
            sb.append("}]}");
            return sb.toString();
        }

        private String formatToolCallChunk(int index, String id, String name, String arguments) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"id\":\"").append(completionId).append("\"");
            sb.append(",\"object\":\"chat.completion.chunk\"");
            sb.append(",\"created\":").append(created);
            sb.append(",\"model\":\"").append(escapeJsonValue(model)).append("\"");
            sb.append(",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{");
            sb.append("\"index\":").append(index);
            if (id != null) {
                sb.append(",\"id\":\"").append(escapeJsonValue(id)).append("\"");
                sb.append(",\"type\":\"function\"");
            }
            if (name != null) {
                sb.append(",\"function\":{\"name\":\"").append(escapeJsonValue(name)).append("\"}");
            }
            if (arguments != null) {
                sb.append(",\"function\":{\"arguments\":\"").append(escapeJsonValue(arguments)).append("\"}");
            }
            sb.append("}]},\"finish_reason\":null}]}");
            return sb.toString();
        }

        private String formatFinishChunk(String finishReason) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"id\":\"").append(completionId).append("\"");
            sb.append(",\"object\":\"chat.completion.chunk\"");
            sb.append(",\"created\":").append(created);
            sb.append(",\"model\":\"").append(escapeJsonValue(model)).append("\"");
            sb.append(",\"choices\":[{\"index\":0,\"delta\":{}");
            sb.append(",\"finish_reason\":\"").append(finishReason).append("\"}]");
            sb.append(",\"usage\":{\"prompt_tokens\":").append(inputTokens);
            sb.append(",\"completion_tokens\":").append(outputTokens);
            sb.append(",\"total_tokens\":").append(inputTokens + outputTokens);
            sb.append("}}");
            return sb.toString();
        }

        @Override public boolean isDone() { return done; }
        @Override public int getInputTokens() { return inputTokens; }
        @Override public int getOutputTokens() { return outputTokens; }
    }

    // ========================
    // 辅助方法
    // ========================

    /**
     * Responses tool_choice → Chat tool_choice。
     * name 嵌套层级适配：{"type":"function","name":"X"} → {"type":"function","function":{"name":"X"}}
     */
    private static JsonNode convertResponsesToolChoiceToChat(JsonNode toolChoice) {
        if (toolChoice.isTextual()) {
            return toolChoice; // "auto" / "required" / "none" → 透传
        }
        if (toolChoice.isObject() && "function".equals(toolChoice.has("type")
                ? toolChoice.get("type").asText() : "")) {
            ObjectNode obj = JSON.createObjectNode();
            obj.put("type", "function");
            ObjectNode func = JSON.createObjectNode();
            func.put("name", toolChoice.has("name") ? toolChoice.get("name").asText() : "");
            obj.set("function", func);
            return obj;
        }
        return toolChoice;
    }

    /**
     * Responses status → Chat finish_reason。
     */
    private static String mapResponsesStatusToChatFinishReason(String status, String incompleteReason, boolean hasToolCalls) {
        if ("incomplete".equals(status)) {
            return "max_output_tokens".equals(incompleteReason) ? "length" : "stop";
        }
        if (hasToolCalls) {
            return "tool_calls";
        }
        return "stop";
    }

    // ========================
    // 通用工具方法
    // ========================

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
