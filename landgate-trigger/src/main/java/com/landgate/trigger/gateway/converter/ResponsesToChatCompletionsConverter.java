package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            copyTextIfExists(ir, dst, "service_tier");
            copyObjectIfExists(ir, dst, "metadata");
            copyIfExists(ir, dst, "parallel_tool_calls");
            copyTextIfExists(ir, dst, "user");
            copyIfExists(ir, dst, "store");
            copyTextIfExists(ir, dst, "safety_identifier");
            copyTextIfExists(ir, dst, "prompt_cache_key");
            copyTextIfExists(ir, dst, "prompt_cache_retention");
            copyIfExists(ir, dst, "top_logprobs");
            if (ir.has("top_logprobs")) {
                dst.put("logprobs", true);
            }
            if (ir.has("include") && containsTextValue(ir.get("include"), "message.output_text.logprobs")) {
                dst.put("logprobs", true);
            }

            // max_output_tokens → max_completion_tokens
            if (ir.has("max_output_tokens")) {
                dst.put("max_completion_tokens", ir.get("max_output_tokens").asInt());
            }

            // 内部 IR stop 扩展 → Chat stop
            if (ir.has("_landgate_stop_sequences") && ir.get("_landgate_stop_sequences").isArray()
                    && ir.get("_landgate_stop_sequences").size() > 0) {
                JsonNode stops = ir.get("_landgate_stop_sequences");
                if (stops.size() == 1) {
                    dst.put("stop", stops.get(0).asText());
                } else {
                    dst.set("stop", stops);
                }
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

            // text.format → response_format
            if (ir.has("text") && ir.get("text").has("format")) {
                JsonNode responseFormat = convertResponsesTextFormatToChat(ir.get("text").get("format"));
                if (responseFormat != null) {
                    dst.set("response_format", responseFormat);
                }
            }
            if (ir.has("text") && ir.get("text").has("verbosity")) {
                dst.set("verbosity", ir.get("text").get("verbosity"));
            }

            // --- input[] → messages[] ---
            ArrayNode messages = JSON.createArrayNode();

            // instructions → system message（放在最前面）
            if (ir.has("instructions") && !isBlankText(ir.get("instructions"))) {
                ObjectNode sysMsg = JSON.createObjectNode();
                sysMsg.put("role", "system");
                sysMsg.put("content", ir.get("instructions").asText());
                messages.add(sysMsg);
            }

            if (ir.has("input")) {
                JsonNode inputNode = ir.get("input");
                if (inputNode.isArray()) {
                    ObjectNode pendingToolCallMsg = null;
                    ArrayNode pendingToolCalls = null;
                    for (JsonNode item : inputNode) {
                        String itemType = item.has("type") ? item.get("type").asText() : null;
                        String role = item.has("role") ? item.get("role").asText() : null;

                        // function_call/custom_tool_call → assistant message with tool_calls
                        if ("function_call".equals(itemType) || "custom_tool_call".equals(itemType)) {
                            if (isBlankText(item.get("call_id")) || isBlankText(item.get("name"))) {
                                log.debug("Responses→Chat: function/custom tool call missing call_id or name, ignored");
                                continue;
                            }
                            if (pendingToolCallMsg == null) {
                                pendingToolCallMsg = JSON.createObjectNode();
                                pendingToolCallMsg.put("role", "assistant");
                                pendingToolCallMsg.put("content", "");
                                pendingToolCalls = JSON.createArrayNode();
                                pendingToolCallMsg.set("tool_calls", pendingToolCalls);
                            }
                            ObjectNode tc = JSON.createObjectNode();
                            tc.put("id", item.get("call_id").asText());
                            if ("custom_tool_call".equals(itemType)) {
                                tc.put("type", "custom");
                                ObjectNode custom = JSON.createObjectNode();
                                custom.put("name", item.get("name").asText());
                                custom.put("input", item.has("input") ? item.get("input").asText() : "");
                                tc.set("custom", custom);
                            } else {
                                tc.put("type", "function");
                                ObjectNode func = JSON.createObjectNode();
                                func.put("name", item.get("name").asText());
                                func.put("arguments", item.has("arguments") ? item.get("arguments").asText() : "{}");
                                tc.set("function", func);
                            }
                            pendingToolCalls.add(tc);
                            continue;
                        }

                        if (pendingToolCallMsg != null) {
                            messages.add(pendingToolCallMsg);
                            pendingToolCallMsg = null;
                            pendingToolCalls = null;
                        }

                        // function_call_output → tool message
                        if ("function_call_output".equals(itemType)) {
                            if (isBlankText(item.get("call_id"))) {
                                log.debug("Responses→Chat: function_call_output missing call_id, ignored");
                                continue;
                            }
                            ObjectNode msg = JSON.createObjectNode();
                            msg.put("role", "tool");
                            msg.put("tool_call_id", item.get("call_id").asText());
                            msg.put("content", item.has("output") ? item.get("output").asText() : "");
                            messages.add(msg);
                            continue;
                        }

                        // 普通 message item
                        if (role != null) {
                            ObjectNode msg = JSON.createObjectNode();
                            msg.put("role", role);

                            JsonNode content = item.get("content");
                            if (content != null && content.isArray()) {
                                // 数组 content → Chat content
                                StringBuilder textContent = new StringBuilder();
                                ArrayNode multiParts = JSON.createArrayNode();
                                boolean hasImage = false;
                                boolean hasConvertiblePart = false;

                                for (JsonNode part : content) {
                                    String partType = part.has("type") ? part.get("type").asText() : "";
                                    switch (partType) {
                                        case "input_text", "output_text", "text" -> {
                                            String t = part.has("text") ? part.get("text").asText() : "";
                                            if (t.isBlank()) {
                                                break;
                                            }
                                            hasConvertiblePart = true;
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
                                        case "refusal" -> {
                                            String t = part.has("refusal") ? part.get("refusal").asText() : "";
                                            if (t.isBlank()) {
                                                break;
                                            }
                                            hasConvertiblePart = true;
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
                                            if (isBlankText(part.get("image_url"))) {
                                                break;
                                            }
                                            imageUrl.put("url", part.get("image_url").asText());
                                            if (part.has("detail") && !part.get("detail").isNull()) {
                                                imageUrl.set("detail", part.get("detail"));
                                            }
                                            p.set("image_url", imageUrl);
                                            multiParts.add(p);
                                            hasConvertiblePart = true;
                                        }
                                        case "input_audio" -> {
                                            if (!part.has("input_audio") || !part.get("input_audio").isObject()
                                                    || !hasChatAudioPayload(part.get("input_audio"))) {
                                                break;
                                            }
                                            hasImage = true;
                                            if (textContent.length() > 0) {
                                                ObjectNode p = JSON.createObjectNode();
                                                p.put("type", "text");
                                                p.put("text", textContent.toString());
                                                multiParts.add(p);
                                                textContent.setLength(0);
                                            }
                                            ObjectNode p = JSON.createObjectNode();
                                            p.put("type", "input_audio");
                                            p.set("input_audio", part.get("input_audio"));
                                            multiParts.add(p);
                                            hasConvertiblePart = true;
                                        }
                                        case "input_file" -> {
                                            if (!hasResponsesFilePayload(part)) {
                                                break;
                                            }
                                            hasImage = true;
                                            if (textContent.length() > 0) {
                                                ObjectNode p = JSON.createObjectNode();
                                                p.put("type", "text");
                                                p.put("text", textContent.toString());
                                                multiParts.add(p);
                                                textContent.setLength(0);
                                            }
                                            ObjectNode p = JSON.createObjectNode();
                                            p.put("type", "file");
                                            ObjectNode file = JSON.createObjectNode();
                                            if (part.has("file_data")) file.set("file_data", part.get("file_data"));
                                            if (part.has("file_id")) file.set("file_id", part.get("file_id"));
                                            if (part.has("filename")) file.set("filename", part.get("filename"));
                                            p.set("file", file);
                                            multiParts.add(p);
                                            hasConvertiblePart = true;
                                        }
                                    }
                                }
                                if (!hasConvertiblePart) {
                                    continue;
                                }
                                if (hasImage) {
                                    msg.set("content", multiParts);
                                } else {
                                    msg.put("content", textContent.toString());
                                }
                            } else if (content != null && content.isTextual()) {
                                msg.put("content", content.asText());
                            } else {
                                continue;
                            }
                            messages.add(msg);
                        }
                    }
                    if (pendingToolCallMsg != null) {
                        messages.add(pendingToolCallMsg);
                    }
                } else if (inputNode.isTextual()) {
                    ObjectNode msg = JSON.createObjectNode();
                    msg.put("role", "user");
                    msg.put("content", inputNode.asText());
                    messages.add(msg);
                }
            }

            dst.set("messages", messages);

            // --- tools ---
            if (ir.has("tools") && ir.get("tools").isArray()) {
                ArrayNode chatTools = JSON.createArrayNode();
                for (JsonNode tool : ir.get("tools")) {
                    String toolType = tool.has("type") ? tool.get("type").asText() : "";
                    if ("function".equals(toolType)) {
                        if (isBlankText(tool.get("name"))) continue;
                        ObjectNode ct = JSON.createObjectNode();
                        ct.put("type", "function");
                        ObjectNode func = JSON.createObjectNode();
                        func.put("name", tool.get("name").asText());
                        if (tool.has("description")) func.put("description", tool.get("description").asText());
                        if (tool.has("parameters")) func.set("parameters", normalizeToolParameters(tool.get("parameters")));
                        if (tool.has("strict")) func.set("strict", tool.get("strict"));
                        ct.set("function", func);
                        chatTools.add(ct);
                    } else if ("custom".equals(toolType)) {
                        if (isBlankText(tool.get("name"))) continue;
                        ObjectNode ct = JSON.createObjectNode();
                        ct.put("type", "custom");
                        ObjectNode custom = JSON.createObjectNode();
                        custom.put("name", tool.get("name").asText());
                        if (tool.has("description")) custom.set("description", tool.get("description"));
                        if (tool.has("format")) custom.set("format", tool.get("format"));
                        ct.set("custom", custom);
                        chatTools.add(ct);
                    } else if (toolType.startsWith("web_search")) {
                        ObjectNode webSearchOptions = convertResponsesWebSearchToolToChatOptions(tool);
                        if (webSearchOptions != null) {
                            dst.set("web_search_options", webSearchOptions);
                        }
                    }
                    // 其他非 function 工具丢弃（Chat Completions 不支持）
                }
                if (chatTools.size() > 0) {
                    dst.set("tools", chatTools);
                }
            }

            // --- tool_choice ---
            if (ir.has("tool_choice")) {
                JsonNode toolChoice = convertResponsesToolChoiceToChat(ir.get("tool_choice"));
                if (toolChoice != null) {
                    dst.set("tool_choice", toolChoice);
                }
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
            dst.put("created", ir.has("created_at") && ir.get("created_at").canConvertToLong()
                    ? ir.get("created_at").asLong()
                    : System.currentTimeMillis() / 1000);
            dst.put("model", ir.has("model") ? ir.get("model").asText() : "unknown");

            // output[] → message content + tool_calls + reasoning_content
            StringBuilder contentText = new StringBuilder();
            StringBuilder reasoningText = new StringBuilder();
            ArrayNode toolCalls = JSON.createArrayNode();
            boolean hasToolCalls = false;

            if (ir.has("output") && ir.get("output").isArray()) {
                for (JsonNode item : ir.get("output")) {
                    String itemType = item.has("type") ? item.get("type").asText() : "";

                    switch (itemType) {
                        case "reasoning" -> {
                            String text = extractReasoningText(item);
                            if (!text.isEmpty()) {
                                if (reasoningText.length() > 0) reasoningText.append("\n");
                                reasoningText.append(text);
                            }
                        }
                        case "message" -> {
                            if (item.has("content") && item.get("content").isArray()) {
                                for (JsonNode part : item.get("content")) {
                                    String partType = part.has("type") ? part.get("type").asText() : "";
                                    if ("output_text".equals(partType) && part.has("text")) {
                                        if (contentText.length() > 0) contentText.append("\n");
                                        contentText.append(part.get("text").asText());
                                    } else if ("refusal".equals(partType) && part.has("refusal")) {
                                        if (contentText.length() > 0) contentText.append("\n");
                                        contentText.append(part.get("refusal").asText());
                                    }
                                }
                            }
                        }
                        case "function_call" -> {
                            if (isBlankText(item.get("call_id")) || isBlankText(item.get("name"))) {
                                log.debug("Responses→Chat: output function_call missing call_id or name, ignored");
                            } else {
                                hasToolCalls = true;
                                ObjectNode tc = JSON.createObjectNode();
                                tc.put("id", item.get("call_id").asText());
                                tc.put("type", "function");
                                ObjectNode func = JSON.createObjectNode();
                                func.put("name", item.get("name").asText());
                                func.put("arguments", item.has("arguments") ? item.get("arguments").asText() : "{}");
                                tc.set("function", func);
                                toolCalls.add(tc);
                            }
                        }
                        case "custom_tool_call" -> {
                            if (isBlankText(item.get("call_id")) || isBlankText(item.get("name"))) {
                                log.debug("Responses→Chat: output custom_tool_call missing call_id or name, ignored");
                            } else {
                                hasToolCalls = true;
                                ObjectNode tc = JSON.createObjectNode();
                                tc.put("id", item.get("call_id").asText());
                                tc.put("type", "custom");
                                ObjectNode custom = JSON.createObjectNode();
                                custom.put("name", item.get("name").asText());
                                custom.put("input", item.has("input") ? item.get("input").asText() : "");
                                tc.set("custom", custom);
                                toolCalls.add(tc);
                            }
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
            message.put("content", contentText.toString());
            if (hasToolCalls) {
                message.set("tool_calls", toolCalls);
            }
            if (reasoningText.length() > 0) {
                message.put("reasoning_content", reasoningText.toString());
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
                if (usage.has("output_tokens_details")
                        && usage.get("output_tokens_details").has("reasoning_tokens")
                        && usage.get("output_tokens_details").get("reasoning_tokens").asInt() > 0) {
                    ObjectNode details = JSON.createObjectNode();
                    details.put("reasoning_tokens", usage.get("output_tokens_details").get("reasoning_tokens").asInt());
                    chatUsage.set("completion_tokens_details", details);
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
        private long created;

        private int inputTokens = 0;
        private int outputTokens = 0;
        private int cachedTokens = 0;
        private int reasoningTokens = 0;

        private final Map<Integer, Integer> outputIndexToToolIndex = new HashMap<>();
        private final Set<Integer> textDeltasSeen = new HashSet<>();
        private final Set<Integer> refusalDeltasSeen = new HashSet<>();
        private final Set<Integer> toolArgumentDeltasSeen = new HashSet<>();
        private final Set<Integer> reasoningDeltasSeen = new HashSet<>();
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
                            if (resp.has("created_at") && resp.get("created_at").canConvertToLong()) {
                                created = resp.get("created_at").asLong();
                            }
                        }
                        if (!sentRole) {
                            output.add("data: " + formatDeltaChunk("assistant", null, null, null));
                            sentRole = true;
                        }
                    }
                    case "response.output_text.delta" -> {
                        String text = root.has("delta") ? root.get("delta").asText() : "";
                        if (!text.isEmpty()) {
                            textDeltasSeen.add(contentKey(root));
                            output.add("data: " + formatDeltaChunk(null, text, null, null));
                        }
                    }
                    case "response.output_text.done" -> {
                        String text = root.has("text") ? root.get("text").asText() : "";
                        int key = contentKey(root);
                        if (!text.isEmpty() && !textDeltasSeen.contains(key)) {
                            textDeltasSeen.add(key);
                            output.add("data: " + formatDeltaChunk(null, text, null, null));
                        }
                    }
                    case "response.content_part.done" -> {
                        JsonNode part = root.path("part");
                        String partType = part.path("type").asText("");
                        int key = contentKey(root);
                        if ("output_text".equals(partType) && !textDeltasSeen.contains(key)) {
                            String text = part.path("text").asText("");
                            if (!text.isEmpty()) {
                                textDeltasSeen.add(key);
                                output.add("data: " + formatDeltaChunk(null, text, null, null));
                            }
                        } else if ("refusal".equals(partType) && !refusalDeltasSeen.contains(key)) {
                            String refusal = part.path("refusal").asText("");
                            if (!refusal.isEmpty()) {
                                refusalDeltasSeen.add(key);
                                output.add("data: " + formatDeltaChunk(null, refusal, null, null));
                            }
                        }
                    }
                    case "response.refusal.delta" -> {
                        String delta = root.has("delta") ? root.get("delta").asText() : "";
                        if (!delta.isEmpty()) {
                            refusalDeltasSeen.add(contentKey(root));
                            output.add("data: " + formatDeltaChunk(null, delta, null, null));
                        }
                    }
                    case "response.refusal.done" -> {
                        String refusal = root.has("refusal") ? root.get("refusal").asText() : "";
                        int key = contentKey(root);
                        if (!refusal.isEmpty() && !refusalDeltasSeen.contains(key)) {
                            refusalDeltasSeen.add(key);
                            output.add("data: " + formatDeltaChunk(null, refusal, null, null));
                        }
                    }
                    case "response.output_item.added" -> {
                        if (root.has("item") && root.get("item").has("type")
                                && ("function_call".equals(root.get("item").get("type").asText())
                                || "custom_tool_call".equals(root.get("item").get("type").asText()))) {
                            JsonNode item = root.get("item");
                            if (isBlankText(item.get("call_id")) || isBlankText(item.get("name"))) {
                                log.debug("Responses→Chat stream: tool call missing call_id or name, ignored");
                                return output;
                            }
                            sawToolCall = true;
                            String callId = item.get("call_id").asText();
                            String toolName = item.get("name").asText();
                            int outputIdx = root.has("output_index") ? root.get("output_index").asInt() : 0;

                            int chatIdx = nextToolCallIndex++;
                            outputIndexToToolIndex.put(outputIdx, chatIdx);

                            if ("custom_tool_call".equals(item.get("type").asText())) {
                                output.add("data: " + formatCustomToolCallChunk(chatIdx, callId, toolName, null));
                            } else {
                                output.add("data: " + formatToolCallChunk(chatIdx, callId, toolName, null));
                            }
                        }
                    }
                    case "response.function_call_arguments.delta" -> {
                        String delta = root.has("delta") ? root.get("delta").asText() : "";
                        if (!delta.isEmpty()) {
                            int outputIdx = root.has("output_index") ? root.get("output_index").asInt() : 0;
                            Integer chatIdx = outputIndexToToolIndex.get(outputIdx);
                            if (chatIdx != null) {
                                toolArgumentDeltasSeen.add(outputIdx);
                                output.add("data: " + formatToolCallChunk(chatIdx, null, null, delta));
                            }
                        }
                    }
                    case "response.output_item.done" -> {
                        if (root.has("item")) {
                            int outputIdx = root.has("output_index") ? root.get("output_index").asInt() : 0;
                            emitFinalOutputItem(output, outputIdx, root.get("item"));
                        }
                    }
                    case "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                        String delta = root.has("delta") ? root.get("delta").asText() : "";
                        if (!delta.isEmpty()) {
                            int outputIdx = root.has("output_index") ? root.get("output_index").asInt() : 0;
                            reasoningDeltasSeen.add(outputIdx);
                            output.add("data: " + formatDeltaChunk(null, null, delta, null));
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

                        // usage
                        if (root.has("usage")) {
                            extractUsage(root.get("usage"));
                        } else if (root.has("response") && root.get("response").has("usage")) {
                            extractUsage(root.get("response").get("usage"));
                        }

                        // finish_reason
                        String finishReason;
                        if (root.has("response")) {
                            JsonNode resp = root.get("response");
                            String respStatus = resp.has("status") ? resp.get("status").asText() : "completed";
                            if ("incomplete".equals(respStatus)) {
                                String incompleteReason = resp.has("incomplete_details")
                                        && resp.get("incomplete_details").has("reason")
                                        ? resp.get("incomplete_details").get("reason").asText() : "";
                                finishReason = mapResponsesIncompleteReasonToChatFinishReason(incompleteReason);
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

        private void emitFinalOutputItem(List<String> output, int outputIdx, JsonNode item) {
            String itemType = item.path("type").asText("");
            if ("message".equals(itemType) && item.has("content") && item.get("content").isArray()) {
                for (int contentIdx = 0; contentIdx < item.get("content").size(); contentIdx++) {
                    JsonNode part = item.get("content").get(contentIdx);
                    String partType = part.path("type").asText("");
                    int key = outputIdx * 10_000 + contentIdx;
                    if ("output_text".equals(partType) && !textDeltasSeen.contains(key)) {
                        String text = part.path("text").asText("");
                        if (!text.isEmpty()) {
                            textDeltasSeen.add(key);
                            output.add("data: " + formatDeltaChunk(null, text, null, null));
                        }
                    } else if ("refusal".equals(partType) && !refusalDeltasSeen.contains(key)) {
                        String refusal = part.path("refusal").asText("");
                        if (!refusal.isEmpty()) {
                            refusalDeltasSeen.add(key);
                            output.add("data: " + formatDeltaChunk(null, refusal, null, null));
                        }
                    }
                }
            } else if ("function_call".equals(itemType)) {
                if (isBlankText(item.get("call_id")) || isBlankText(item.get("name"))) {
                    log.debug("Responses→Chat stream: final function_call missing call_id or name, ignored");
                    return;
                }
                sawToolCall = true;
                Integer chatIdx = outputIndexToToolIndex.get(outputIdx);
                if (chatIdx == null) {
                    chatIdx = nextToolCallIndex++;
                    outputIndexToToolIndex.put(outputIdx, chatIdx);
                    output.add("data: " + formatToolCallChunk(chatIdx,
                            item.path("call_id").asText(""),
                            item.path("name").asText(""),
                            null));
                }
                String arguments = item.path("arguments").asText("");
                if (!arguments.isEmpty() && !toolArgumentDeltasSeen.contains(outputIdx)) {
                    toolArgumentDeltasSeen.add(outputIdx);
                    output.add("data: " + formatToolCallChunk(chatIdx, null, null, arguments));
                }
            } else if ("custom_tool_call".equals(itemType)) {
                if (isBlankText(item.get("call_id")) || isBlankText(item.get("name"))) {
                    log.debug("Responses→Chat stream: final custom_tool_call missing call_id or name, ignored");
                    return;
                }
                sawToolCall = true;
                Integer chatIdx = outputIndexToToolIndex.get(outputIdx);
                if (chatIdx == null) {
                    chatIdx = nextToolCallIndex++;
                    outputIndexToToolIndex.put(outputIdx, chatIdx);
                    output.add("data: " + formatCustomToolCallChunk(chatIdx,
                            item.path("call_id").asText(""),
                            item.path("name").asText(""),
                            null));
                }
                String input = item.path("input").asText("");
                if (!input.isEmpty() && !toolArgumentDeltasSeen.contains(outputIdx)) {
                    toolArgumentDeltasSeen.add(outputIdx);
                    output.add("data: " + formatCustomToolCallChunk(chatIdx, null, null, input));
                }
            } else if ("reasoning".equals(itemType) && !reasoningDeltasSeen.contains(outputIdx)
                    && (item.has("content") || item.has("summary"))) {
                String reasoning = extractReasoningText(item);
                if (!reasoning.isEmpty()) {
                    reasoningDeltasSeen.add(outputIdx);
                    output.add("data: " + formatDeltaChunk(null, null, reasoning, null));
                }
            }
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

        private int contentKey(JsonNode root) {
            int outputIdx = root.has("output_index") ? root.get("output_index").asInt() : 0;
            int contentIdx = root.has("content_index") ? root.get("content_index").asInt() : 0;
            return outputIdx * 10_000 + contentIdx;
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
            if (name != null || arguments != null) {
                sb.append(",\"function\":{");
                boolean hasFunctionField = false;
                if (name != null) {
                    sb.append("\"name\":\"").append(escapeJsonValue(name)).append("\"");
                    hasFunctionField = true;
                }
                if (arguments != null) {
                    if (hasFunctionField) sb.append(",");
                    sb.append("\"arguments\":\"").append(escapeJsonValue(arguments)).append("\"");
                }
                sb.append("}");
            }
            sb.append("}]},\"finish_reason\":null}]}");
            return sb.toString();
        }

        private String formatCustomToolCallChunk(int index, String id, String name, String input) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"id\":\"").append(completionId).append("\"");
            sb.append(",\"object\":\"chat.completion.chunk\"");
            sb.append(",\"created\":").append(created);
            sb.append(",\"model\":\"").append(escapeJsonValue(model)).append("\"");
            sb.append(",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{");
            sb.append("\"index\":").append(index);
            if (id != null) {
                sb.append(",\"id\":\"").append(escapeJsonValue(id)).append("\"");
                sb.append(",\"type\":\"custom\"");
            }
            if (name != null || input != null) {
                sb.append(",\"custom\":{");
                boolean hasCustomField = false;
                if (name != null) {
                    sb.append("\"name\":\"").append(escapeJsonValue(name)).append("\"");
                    hasCustomField = true;
                }
                if (input != null) {
                    if (hasCustomField) sb.append(",");
                    sb.append("\"input\":\"").append(escapeJsonValue(input)).append("\"");
                }
                sb.append("}");
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
            if (cachedTokens > 0) {
                sb.append(",\"prompt_tokens_details\":{\"cached_tokens\":").append(cachedTokens).append("}");
            }
            if (reasoningTokens > 0) {
                sb.append(",\"completion_tokens_details\":{\"reasoning_tokens\":").append(reasoningTokens).append("}");
            }
            sb.append("}}");
            return sb.toString();
        }

        private void extractUsage(JsonNode usage) {
            if (usage.has("input_tokens")) inputTokens = usage.get("input_tokens").asInt();
            if (usage.has("output_tokens")) outputTokens = usage.get("output_tokens").asInt();
            if (usage.has("input_tokens_details") && usage.get("input_tokens_details").has("cached_tokens")) {
                cachedTokens = usage.get("input_tokens_details").get("cached_tokens").asInt();
            }
            if (usage.has("output_tokens_details") && usage.get("output_tokens_details").has("reasoning_tokens")) {
                reasoningTokens = usage.get("output_tokens_details").get("reasoning_tokens").asInt();
            }
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
     * name 嵌套层级适配：
     * {"type":"function","name":"X"} → {"type":"function","function":{"name":"X"}}
     * {"type":"custom","name":"X"} → {"type":"custom","custom":{"name":"X"}}
     */
    private static JsonNode convertResponsesToolChoiceToChat(JsonNode toolChoice) {
        if (toolChoice.isTextual()) {
            return isSupportedToolChoiceMode(toolChoice.asText()) ? toolChoice : null;
        }
        if (toolChoice.isObject() && "function".equals(toolChoice.has("type")
                ? toolChoice.get("type").asText() : "")) {
            if (isBlankText(toolChoice.get("name"))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put("type", "function");
            ObjectNode func = JSON.createObjectNode();
            func.put("name", toolChoice.get("name").asText());
            obj.set("function", func);
            return obj;
        }
        if (toolChoice.isObject() && "custom".equals(toolChoice.has("type")
                ? toolChoice.get("type").asText() : "")) {
            if (isBlankText(toolChoice.get("name"))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put("type", "custom");
            ObjectNode custom = JSON.createObjectNode();
            custom.put("name", toolChoice.get("name").asText());
            obj.set("custom", custom);
            return obj;
        }
        if (toolChoice.isObject() && "allowed_tools".equals(toolChoice.has("type")
                ? toolChoice.get("type").asText() : "")) {
            ObjectNode obj = JSON.createObjectNode();
            obj.put("type", "allowed_tools");
            ObjectNode allowed = JSON.createObjectNode();
            copyIfExists(toolChoice, allowed, "mode");
            if (toolChoice.has("tools") && toolChoice.get("tools").isArray()) {
                ArrayNode tools = JSON.createArrayNode();
                for (JsonNode tool : toolChoice.get("tools")) {
                    JsonNode converted = convertResponsesAllowedToolToChat(tool);
                    if (converted != null) tools.add(converted);
                }
                if (tools.size() == 0) return null;
                allowed.set("tools", tools);
            }
            obj.set("allowed_tools", allowed);
            return obj;
        }
        return null;
    }

    private static boolean isSupportedToolChoiceMode(String mode) {
        return "auto".equals(mode) || "none".equals(mode) || "required".equals(mode);
    }

    private static JsonNode convertResponsesAllowedToolToChat(JsonNode tool) {
        if (tool == null || !tool.isObject()) return null;
        String type = tool.path("type").asText("");
        if ("function".equals(type)) {
            if (isBlankText(tool.get("name"))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put("type", "function");
            ObjectNode func = JSON.createObjectNode();
            func.put("name", tool.get("name").asText());
            obj.set("function", func);
            return obj;
        }
        if ("custom".equals(type)) {
            if (isBlankText(tool.get("name"))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put("type", "custom");
            ObjectNode custom = JSON.createObjectNode();
            custom.put("name", tool.get("name").asText());
            obj.set("custom", custom);
            return obj;
        }
        return null;
    }

    private static JsonNode convertResponsesTextFormatToChat(JsonNode format) {
        String type = format.path("type").asText("");
        if ("json_schema".equals(type)) {
            if (isBlankText(format.get("name"))) return null;
            if (!format.has("schema") || format.get("schema").isNull()) return null;
            ObjectNode responseFormat = JSON.createObjectNode();
            responseFormat.put("type", "json_schema");
            ObjectNode jsonSchema = JSON.createObjectNode();
            jsonSchema.set("name", format.get("name"));
            if (format.has("description")) jsonSchema.set("description", format.get("description"));
            jsonSchema.set("schema", format.get("schema"));
            if (format.has("strict")) jsonSchema.set("strict", format.get("strict"));
            responseFormat.set("json_schema", jsonSchema);
            return responseFormat;
        }
        if ("json_object".equals(type)) {
            ObjectNode responseFormat = JSON.createObjectNode();
            responseFormat.put("type", "json_object");
            return responseFormat;
        }
        if ("text".equals(type)) {
            ObjectNode responseFormat = JSON.createObjectNode();
            responseFormat.put("type", "text");
            return responseFormat;
        }
        return null;
    }

    private static ObjectNode convertResponsesWebSearchToolToChatOptions(JsonNode tool) {
        if (tool == null || !tool.isObject()) return null;
        ObjectNode options = JSON.createObjectNode();
        copyIfExists(tool, options, "search_context_size");

        JsonNode locationNode = tool.get("user_location");
        if (locationNode != null && locationNode.isObject()) {
            ObjectNode userLocation = JSON.createObjectNode();
            userLocation.put("type", "approximate");
            ObjectNode approximate = JSON.createObjectNode();
            copyIfExists(locationNode, approximate, "country");
            copyIfExists(locationNode, approximate, "region");
            copyIfExists(locationNode, approximate, "city");
            copyIfExists(locationNode, approximate, "timezone");
            userLocation.set("approximate", approximate);
            options.set("user_location", userLocation);
        }
        return options;
    }

    private static boolean containsTextValue(JsonNode node, String value) {
        if (node == null || value == null) return false;
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (value.equals(item.asText(null))) return true;
            }
        }
        return false;
    }

    private static boolean isBlankText(JsonNode node) {
        return node == null || node.isNull() || node.asText("").isBlank();
    }

    private static boolean hasResponsesFilePayload(JsonNode part) {
        return !isBlankText(part.get("file_data")) || !isBlankText(part.get("file_id"));
    }

    private static boolean hasChatAudioPayload(JsonNode inputAudio) {
        return !isBlankText(inputAudio.get("data")) && !isBlankText(inputAudio.get("format"));
    }

    private static JsonNode normalizeToolParameters(JsonNode parameters) {
        if (parameters != null && parameters.isObject()) {
            return parameters;
        }
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", JSON.createObjectNode());
        return schema;
    }

    /**
     * Responses status → Chat finish_reason。
     */
    private static String mapResponsesStatusToChatFinishReason(String status, String incompleteReason, boolean hasToolCalls) {
        if ("incomplete".equals(status)) {
            return mapResponsesIncompleteReasonToChatFinishReason(incompleteReason);
        }
        if (hasToolCalls) {
            return "tool_calls";
        }
        return "stop";
    }

    private static String mapResponsesIncompleteReasonToChatFinishReason(String incompleteReason) {
        return "content_filter".equals(incompleteReason) ? "content_filter"
                : "max_output_tokens".equals(incompleteReason) ? "length"
                : "length";
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
