package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.types.enums.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 协议翻译服务 —— Anthropic ↔ OpenAI 请求/响应格式双向转换。
 * <p>
 * 当客户端请求格式（由 URL 路径决定）与上游账号格式（由 account.platform 决定）不同时，
 * 对请求 body 和响应 body 做协议互转，使客户端和上游各自收到自己能理解的格式。
 * <p>
 * 当前支持：Anthropic 客户端 → OpenAI 上游（最常见场景：Claude Code 调用 GPT 模型）。
 */
@Slf4j
@Component
public class ProtocolTranslationService {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ========================
    // 请求翻译：Anthropic → OpenAI
    // ========================

    /**
     * 将 Anthropic Messages API 请求体转换为 OpenAI Chat Completions 请求体。
     *
     * @param body Anthropic 格式的请求 JSON 字符串
     * @return OpenAI 格式的请求 JSON 字符串；源格式==目标格式时原样返回
     */
    public String translateRequest(String body, Platform from, Platform to) {
        if (from == to) return body;
        if (from == Platform.ANTHROPIC && to == Platform.OPENAI) {
            return anthropicToOpenAIRequest(body);
        }
        // 其他方向暂不支持，原样透传
        log.debug("No request translator for {}→{}, passing through", from, to);
        return body;
    }

    private String anthropicToOpenAIRequest(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            // 直接透传的参数
            copyIfExists(src, dst, "model");
            copyIfExists(src, dst, "stream");
            copyIfExists(src, dst, "max_tokens");
            copyIfExists(src, dst, "temperature");
            copyIfExists(src, dst, "top_p");

            // stop_sequences → stop（Anthropic 用数组，OpenAI 用单字符串或数组，这里取第一个）
            if (src.has("stop_sequences") && src.get("stop_sequences").isArray()
                    && src.get("stop_sequences").size() > 0) {
                dst.put("stop", src.get("stop_sequences").get(0).asText());
            }

            // 构建 messages 数组
            ArrayNode messages = JSON.createArrayNode();

            // Anthropic system (顶层数组) → OpenAI messages[0] role=system
            if (src.has("system") && src.get("system").isArray()) {
                String systemText = extractTextFromContentBlocks(src.get("system"));
                if (systemText != null && !systemText.isEmpty()) {
                    ObjectNode sysMsg = JSON.createObjectNode();
                    sysMsg.put("role", "system");
                    sysMsg.put("content", systemText);
                    messages.add(sysMsg);
                }
            }

            // 转换 user/assistant 消息：content 数组 → 字符串
            if (src.has("messages") && src.get("messages").isArray()) {
                for (JsonNode msg : src.get("messages")) {
                    String role = msg.has("role") ? msg.get("role").asText() : "user";
                    String content = extractTextFromContentBlocks(msg.get("content"));
                    if (content == null || content.isEmpty()) continue;

                    ObjectNode openAIMsg = JSON.createObjectNode();
                    openAIMsg.put("role", role);
                    openAIMsg.put("content", content);
                    messages.add(openAIMsg);
                }
            }

            dst.set("messages", messages);
            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Failed to translate Anthropic→OpenAI request, passing through: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 从 Anthropic content 数组（[{type: "text", text: "..."}, ...]）中提取纯文本。
     * 仅拼接 text 类型的块，忽略 tool_use、image 等。
     */
    private static String extractTextFromContentBlocks(JsonNode content) {
        if (content == null) return null;
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if (block.has("type") && "text".equals(block.get("type").asText())
                    && block.has("text")) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(block.get("text").asText());
            }
        }
        return sb.toString();
    }

    // ========================
    // 非流式响应翻译：OpenAI → Anthropic
    // ========================

    /**
     * 将非流式响应从上游格式转换为客户端格式。
     *
     * @param body 上游响应 JSON 字符串
     * @param from 上游平台
     * @param to   客户端平台
     * @return 翻译后的响应 JSON 字符串；源格式==目标格式时原样返回
     */
    public String translateResponse(String body, Platform from, Platform to) {
        if (from == to) return body;
        if (from == Platform.OPENAI && to == Platform.ANTHROPIC) {
            return openAIToAnthropicResponse(body);
        }
        log.debug("No response translator for {}→{}, passing through", from, to);
        return body;
    }

    private String openAIToAnthropicResponse(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            // 基础字段
            dst.put("id", src.has("id") ? src.get("id").asText() : "msg_" + UUID.randomUUID());
            dst.put("type", "message");
            dst.put("role", "assistant");
            dst.put("model", src.has("model") ? src.get("model").asText() : "unknown");

            // 从 choices[0] 提取 stop_reason
            String stopReason = "end_turn";
            String messageContent = "";
            if (src.has("choices") && src.get("choices").isArray() && src.get("choices").size() > 0) {
                JsonNode choice = src.get("choices").get(0);
                if (choice.has("finish_reason")) {
                    stopReason = mapFinishReason(choice.get("finish_reason").asText());
                }
                if (choice.has("message") && choice.get("message").has("content")) {
                    messageContent = choice.get("message").get("content").asText();
                }
            }
            dst.put("stop_reason", stopReason);

            // content 数组（Anthropic 格式）
            ArrayNode content = JSON.createArrayNode();
            ObjectNode textBlock = JSON.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", messageContent);
            content.add(textBlock);
            dst.set("content", content);

            // usage 字段重命名
            if (src.has("usage")) {
                JsonNode usage = src.get("usage");
                ObjectNode anthropicUsage = JSON.createObjectNode();
                if (usage.has("prompt_tokens")) {
                    anthropicUsage.put("input_tokens", usage.get("prompt_tokens").asInt());
                }
                if (usage.has("completion_tokens")) {
                    anthropicUsage.put("output_tokens", usage.get("completion_tokens").asInt());
                }
                if (usage.has("total_tokens")) {
                    anthropicUsage.put("total_tokens", usage.get("total_tokens").asInt());
                }
                dst.set("usage", anthropicUsage);
            }

            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Failed to translate OpenAI→Anthropic response, passing through: {}", e.getMessage());
            return body;
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

    // ========================
    // 流式响应翻译：OpenAI SSE → Anthropic SSE
    // ========================

    /**
     * OpenAI SSE → Anthropic SSE 流式翻译器（状态机）。
     * <p>
     * 用法：每收到上游一行原始 SSE 就调用 {@link #feed(String)}，
     * 返回需要写入客户端的 Anthropic SSE 行列表（可能为 0~N 行）。
     * {@code [DONE]} 由调用方在上层处理，不会传入本方法。
     */
    public static class SSEStreamTranslator {

        private enum State { INIT, BLOCK_STARTED, STREAMING, DONE }

        private State state = State.INIT;
        private final String model;
        private final String messageId;
        private int completionTokens = 0;
        private String stopReason = "end_turn";

        public SSEStreamTranslator(String model) {
            this.model = model;
            this.messageId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }

        /**
         * 消费一行上游 OpenAI SSE data 行的 JSON 部分（"data: " 之后的内容）。
         *
         * @param jsonLine SSE data 行的 JSON 内容（不含 "data: " 前缀）
         * @return 需要写入客户端的 Anthropic SSE 行列表（含 "event:" / "data:" 前缀）
         */
        public List<String> feed(String jsonLine) {
            List<String> output = new ArrayList<>();
            if (state == State.DONE || jsonLine == null || jsonLine.isBlank()) {
                return output;
            }

            try {
                JsonNode chunk = JSON.readTree(jsonLine);
                if (!chunk.has("choices") || chunk.get("choices").size() == 0) {
                    return output;
                }
                JsonNode choice = chunk.get("choices").get(0);

                // 捕获 usage（如果有，通常在最后一个 chunk）
                if (chunk.has("usage")) {
                    JsonNode u = chunk.get("usage");
                    if (u.has("completion_tokens")) {
                        completionTokens = u.get("completion_tokens").asInt();
                    }
                }

                // 检测 finish_reason
                boolean hasFinish = choice.has("finish_reason")
                        && !choice.get("finish_reason").isNull()
                        && !"null".equals(choice.get("finish_reason").asText());

                JsonNode delta = choice.has("delta") ? choice.get("delta") : null;
                if (delta == null && !hasFinish) return output;

                switch (state) {
                    case INIT -> {
                        if (delta != null && delta.has("role")) {
                            // 第一个 chunk：发送 message_start + content_block_start
                            output.add("event: message_start");
                            output.add("data: " + formatMessageStart());
                            output.add("");
                            output.add("event: content_block_start");
                            output.add("data: " + formatContentBlockStart(0));
                            output.add("");
                            state = State.BLOCK_STARTED;
                        }
                        // 如果 INIT 状态下就有 finish_reason（空响应），直接结束
                        if (hasFinish) {
                            finish(output);
                        }
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

                // 最后一个有内容或 finish 的 chunk → 发送结束事件
                if (hasFinish || (delta != null && delta.has("content") && delta.get("content").asText().isEmpty())) {
                    if (hasFinish && choice.has("finish_reason")) {
                        stopReason = mapFinishReason(choice.get("finish_reason").asText());
                    }
                    finish(output);
                }
            } catch (Exception e) {
                log.debug("SSE translation error, skipping line: {}", e.getMessage());
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

        /** @return 是否已完成（发送了结束事件） */
        public boolean isDone() {
            return state == State.DONE;
        }

        /** @return 从 SSE 流中提取的 completion_tokens 数量 */
        public int getCompletionTokens() {
            return completionTokens;
        }

        // ---- Anthropic SSE 事件格式化 ----

        private String formatMessageStart() {
            return escapeJson(String.format(
                    "{\"type\":\"message_start\",\"message\":{\"id\":\"%s\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"%s\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}",
                    messageId, model));
        }

        private String formatContentBlockStart(int index) {
            return escapeJson(String.format(
                    "{\"type\":\"content_block_start\",\"index\":%d,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                    index));
        }

        private String formatContentBlockDelta(int index, String text) {
            return escapeJson(String.format(
                    "{\"type\":\"content_block_delta\",\"index\":%d,\"delta\":{\"type\":\"text_delta\",\"text\":\"%s\"}}",
                    index, escapeJsonValue(text)));
        }

        private String formatContentBlockStop(int index) {
            return escapeJson(String.format(
                    "{\"type\":\"content_block_stop\",\"index\":%d}", index));
        }

        private String formatMessageDelta() {
            return escapeJson(String.format(
                    "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"%s\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":%d}}",
                    stopReason, completionTokens));
        }

        private String formatMessageStop() {
            return escapeJson("{\"type\":\"message_stop\"}");
        }

        // ---- JSON 转义 ----

        /** 转义 JSON 字符串值中的特殊字符（用于嵌入 JSON 内的 text 字段） */
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

        /** 对完整的 SSE data 行做最小转义（方法签名以匹配上层调用，实际 JSON 已在内格式化） */
        private static String escapeJson(String s) {
            return s;
        }
    }

    // ---- 工具方法 ----

    private static void copyIfExists(JsonNode src, ObjectNode dst, String field) {
        if (src.has(field) && !src.get(field).isNull()) {
            dst.set(field, src.get(field));
        }
    }
}
