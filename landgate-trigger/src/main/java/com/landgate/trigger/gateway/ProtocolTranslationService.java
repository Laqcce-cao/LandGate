package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.trigger.gateway.converter.ConverterRegistry;
import com.landgate.trigger.gateway.converter.ProtocolConverter;
import com.landgate.types.enums.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 协议翻译服务 —— Hub-and-Spoke 架构的翻译入口。
 * <p>
 * 委托给 {@link ConverterRegistry} 中的 {@link ProtocolConverter} 完成翻译，
 * 翻译路径为：客户端格式 → IR（Anthropic Messages 格式） → 上游格式。
 * <p>
 * 内部的旧版点对点静态内部类（流式翻译器）保留以兼容现有调用方，
 * 后续逐步迁移到 {@link ProtocolConverter#createStreamToIR} / {@link ProtocolConverter#createStreamFromIR}。
 */
@Slf4j
@Component
public class ProtocolTranslationService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConverterRegistry converterRegistry;

    public ProtocolTranslationService(ConverterRegistry converterRegistry) {
        this.converterRegistry = converterRegistry;
    }

    // ========================
    // Hub-and-Spoke 请求翻译
    // ========================

    /**
     * 将请求 body 从客户端格式翻译为上游账号格式（Hub-and-Spoke）。
     * <p>
     * 翻译路径：客户端格式 → IR → 上游格式。
     * 若客户端格式与上游格式相同，直接返回原 body。
     *
     * @param body 客户端请求 JSON 字符串
     * @param from 客户端平台（请求格式）
     * @param to   上游账号平台（目标格式）
     * @return 翻译后的请求 JSON 字符串
     */
    public String translateRequest(String body, Platform from, Platform to) {
        if (from == to) return body;

        String fromFormat = platformToFormatId(from);
        String toFormat = platformToFormatId(to);
        if (fromFormat == null || toFormat == null) {
            log.debug("No format mapping for {}→{}, passing through", from, to);
            return body;
        }

        ProtocolConverter clientConv = converterRegistry.get(fromFormat);
        ProtocolConverter upstreamConv = converterRegistry.get(toFormat);
        if (clientConv == null || upstreamConv == null) {
            log.debug("No converter for {}→{}, passing through", from, to);
            return body;
        }

        try {
            // 客户端格式 → IR → 上游格式
            JsonNode ir = clientConv.requestToIR(body);
            return upstreamConv.requestFromIR(ir);
        } catch (Exception e) {
            log.warn("Hub-and-Spoke request translation failed for {}→{}, passing through: {}",
                    from, to, e.getMessage());
            return body;
        }
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

    /**
     * 将 OpenAI Chat Completions 请求体转换为 Anthropic Messages API 请求体。
     */
    private String openAIToAnthropicRequest(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            // 直接透传的参数
            copyIfExists(src, dst, "model");
            copyIfExists(src, dst, "stream");
            copyIfExists(src, dst, "max_tokens");
            copyIfExists(src, dst, "temperature");
            copyIfExists(src, dst, "top_p");

            // stop → stop_sequences（OpenAI 用单字符串或数组，Anthropic 用数组）
            if (src.has("stop")) {
                JsonNode stop = src.get("stop");
                ArrayNode stopSeq = JSON.createArrayNode();
                if (stop.isArray()) {
                    for (JsonNode s : stop) {
                        stopSeq.add(s.asText());
                    }
                } else {
                    stopSeq.add(stop.asText());
                }
                dst.set("stop_sequences", stopSeq);
            }

            // 构建 Anthropic messages 数组 + 提取 system
            ArrayNode anthropicMessages = JSON.createArrayNode();
            if (src.has("messages") && src.get("messages").isArray()) {
                for (JsonNode msg : src.get("messages")) {
                    String role = msg.has("role") ? msg.get("role").asText() : "user";
                    JsonNode contentNode = msg.get("content");

                    if ("system".equals(role)) {
                        // OpenAI system 消息 → Anthropic 顶层 system 字段（字符串）
                        String systemText = contentNode != null ? contentNode.asText() : "";
                        if (!systemText.isEmpty()) {
                            dst.put("system", systemText);
                        }
                        continue;
                    }

                    // user/assistant 消息：content 字符串 → [{type:"text", text:"..."}]
                    ObjectNode anthropicMsg = JSON.createObjectNode();
                    anthropicMsg.put("role", role);
                    ArrayNode contentBlocks = JSON.createArrayNode();
                    ObjectNode textBlock = JSON.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", contentNode != null ? contentNode.asText() : "");
                    contentBlocks.add(textBlock);
                    anthropicMsg.set("content", contentBlocks);
                    anthropicMessages.add(anthropicMsg);
                }
            }

            dst.set("messages", anthropicMessages);
            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Failed to translate OpenAI→Anthropic request, passing through: {}", e.getMessage());
            return body;
        }
    }

    // ========================
    // 请求翻译：Responses API ↔ Chat Completions
    // ========================

    /**
     * 将 OpenAI Responses API 请求体转换为 Chat Completions 请求体。
     * <p>
     * 关键转换：{@code input} → {@code messages}，{@code max_output_tokens} → {@code max_tokens}，
     * 强制添加 {@code "stream": true}（Responses API 无 stream 字段，默认流式）。
     */
    private String responsesToChatCompletions(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            // 直接透传的参数
            copyIfExists(src, dst, "model");
            copyIfExists(src, dst, "temperature");
            copyIfExists(src, dst, "top_p");

            // max_output_tokens → max_tokens
            if (src.has("max_output_tokens")) {
                dst.put("max_tokens", src.get("max_output_tokens").asInt());
            }

            // 强制添加 stream: true（Responses API 默认流式）
            dst.put("stream", true);
            // 要求上游在流式响应的最后一个 chunk 中包含 usage 字段
            ObjectNode streamOptions = JSON.createObjectNode();
            streamOptions.put("include_usage", true);
            dst.set("stream_options", streamOptions);

            // stop → stop（字段名相同）
            copyIfExists(src, dst, "stop");

            // input → messages（字段重命名 + instructions 处理）
            ArrayNode messages = JSON.createArrayNode();

            // instructions 字段 → system 消息前置
            if (src.has("instructions") && !src.get("instructions").isNull()) {
                ObjectNode sysMsg = JSON.createObjectNode();
                sysMsg.put("role", "system");
                sysMsg.put("content", src.get("instructions").asText());
                messages.add(sysMsg);
            }

            if (src.has("input") && src.get("input").isArray()) {
                for (JsonNode item : src.get("input")) {
                    String role = item.has("role") ? item.get("role").asText() : "user";
                    String content = extractTextOrStringContent(item);
                    if (content == null || content.isEmpty()) continue;

                    // system 角色已在 messages 中 → 跳过（或追加；若已有 instructions 则追加为第二条 system）
                    if ("system".equals(role) && messages.size() > 0
                            && "system".equals(messages.get(0).get("role").asText())) {
                        // 已有 instructions 作为 system，追加内容
                        String existingContent = messages.get(0).get("content").asText();
                        messages.remove(0);
                        ObjectNode mergedSys = JSON.createObjectNode();
                        mergedSys.put("role", "system");
                        mergedSys.put("content", existingContent + "\n" + content);
                        messages.insert(0, mergedSys);
                        continue;
                    }

                    ObjectNode msg = JSON.createObjectNode();
                    msg.put("role", role);
                    msg.put("content", content);
                    messages.add(msg);
                }
            }

            dst.set("messages", messages);
            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Failed to translate Responses→ChatCompletions request, passing through: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 将 Chat Completions 请求体转换为 Responses API 请求体。
     * <p>
     * 关键转换：{@code messages} → {@code input}，{@code max_tokens} → {@code max_output_tokens}，
     * 移除 {@code stream} 字段。
     */
    private String chatCompletionsToResponses(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            copyIfExists(src, dst, "model");
            copyIfExists(src, dst, "temperature");
            copyIfExists(src, dst, "top_p");
            copyIfExists(src, dst, "stop");

            // max_tokens → max_output_tokens
            if (src.has("max_tokens")) {
                dst.put("max_output_tokens", src.get("max_tokens").asInt());
            }

            // messages → input（字段重命名 + system 消息 → instructions）
            ArrayNode input = JSON.createArrayNode();
            if (src.has("messages") && src.get("messages").isArray()) {
                for (JsonNode msg : src.get("messages")) {
                    String role = msg.has("role") ? msg.get("role").asText() : "user";
                    JsonNode contentNode = msg.get("content");

                    if ("system".equals(role)) {
                        // system 消息 → 顶层 instructions
                        String text = contentNode != null ? contentNode.asText() : "";
                        if (!text.isEmpty()) {
                            dst.put("instructions", text);
                        }
                        continue;
                    }

                    ObjectNode item = JSON.createObjectNode();
                    item.put("role", role);
                    item.put("content", contentNode != null ? contentNode.asText() : "");
                    input.add(item);
                }
            }

            dst.set("input", input);
            // 注意：不输出 stream 字段，Responses API 默认流式
            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Failed to translate ChatCompletions→Responses request, passing through: {}", e.getMessage());
            return body;
        }
    }

    // ========================
    // 请求翻译：Responses API ↔ Anthropic
    // ========================

    /**
     * 将 Responses API 请求体转换为 Anthropic Messages 请求体。
     * <p>
     * 关键转换：{@code input} → {@code messages}（content 字符串 → [{type:"text",text}] 数组），
     * system 消息 → 顶层 {@code system} 字段，{@code max_output_tokens} → {@code max_tokens}。
     */
    private String responsesToAnthropic(String body) {
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

            // 构建 Anthropic messages 数组 + 提取 system
            ArrayNode anthropicMessages = JSON.createArrayNode();
            StringBuilder systemText = new StringBuilder();

            // instructions → system 文本
            if (src.has("instructions") && !src.get("instructions").isNull()) {
                systemText.append(src.get("instructions").asText());
            }

            if (src.has("input") && src.get("input").isArray()) {
                for (JsonNode item : src.get("input")) {
                    String role = item.has("role") ? item.get("role").asText() : "user";
                    String content = extractTextOrStringContent(item);

                    if ("system".equals(role)) {
                        if (systemText.length() > 0) systemText.append("\n");
                        systemText.append(content != null ? content : "");
                        continue;
                    }

                    if (content == null || content.isEmpty()) continue;

                    ObjectNode anthropicMsg = JSON.createObjectNode();
                    anthropicMsg.put("role", role);
                    ArrayNode contentBlocks = JSON.createArrayNode();
                    ObjectNode textBlock = JSON.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", content);
                    contentBlocks.add(textBlock);
                    anthropicMsg.set("content", contentBlocks);
                    anthropicMessages.add(anthropicMsg);
                }
            }

            if (systemText.length() > 0) {
                dst.put("system", systemText.toString());
            }

            dst.set("messages", anthropicMessages);
            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Failed to translate Responses→Anthropic request, passing through: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 将 Anthropic Messages 请求体转换为 Responses API 请求体。
     * <p>
     * 关键转换：顶层 {@code system} → {@code input} 前插 system 消息，
     * content 数组 → 提取纯文本字符串，{@code max_tokens} → {@code max_output_tokens}。
     */
    private String anthropicToResponses(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            copyIfExists(src, dst, "model");
            copyIfExists(src, dst, "temperature");
            copyIfExists(src, dst, "top_p");

            // max_tokens → max_output_tokens
            if (src.has("max_tokens")) {
                dst.put("max_output_tokens", src.get("max_tokens").asInt());
            }

            // stop_sequences → stop（取第一个或保持数组）
            if (src.has("stop_sequences") && src.get("stop_sequences").isArray()
                    && src.get("stop_sequences").size() > 0) {
                dst.put("stop", src.get("stop_sequences").get(0).asText());
            }

            // 构建 input 数组
            ArrayNode input = JSON.createArrayNode();

            // Anthropic system (顶层) → input 第一条 system 消息 或 instructions
            if (src.has("system")) {
                String sysText;
                if (src.get("system").isArray()) {
                    sysText = extractTextFromContentBlocks(src.get("system"));
                } else {
                    sysText = src.get("system").asText();
                }
                if (sysText != null && !sysText.isEmpty()) {
                    dst.put("instructions", sysText);
                }
            }

            // messages → input（content 数组 → 纯文本字符串）
            if (src.has("messages") && src.get("messages").isArray()) {
                for (JsonNode msg : src.get("messages")) {
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
            log.warn("Failed to translate Anthropic→Responses request, passing through: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 从 Responses API input 项或 content 字段提取纯文本字符串。
     * 支持：{@code "content": "string"} 和 {@code "content": [{type:"input_text", text:"..."}]} 两种格式。
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
    // ========================

    // ========================
    // Hub-and-Spoke 响应翻译
    // ========================

    /**
     * 将非流式响应从上游格式翻译为客户端格式（Hub-and-Spoke）。
     * <p>
     * 翻译路径：上游格式 → IR → 客户端格式。
     * 若上游格式与客户端格式相同，直接返回原 body。
     *
     * @param body 上游响应 JSON 字符串
     * @param from 上游账号平台（上游格式）
     * @param to   客户端平台（目标格式）
     * @return 翻译后的响应 JSON 字符串
     */
    public String translateResponse(String body, Platform from, Platform to) {
        if (from == to) return body;

        String fromFormat = platformToFormatId(from);
        String toFormat = platformToFormatId(to);
        if (fromFormat == null || toFormat == null) {
            log.debug("No format mapping for {}→{}, passing through", from, to);
            return body;
        }

        ProtocolConverter upstreamConv = converterRegistry.get(fromFormat);
        ProtocolConverter clientConv = converterRegistry.get(toFormat);
        if (upstreamConv == null || clientConv == null) {
            log.debug("No converter for {}→{}, passing through", from, to);
            return body;
        }

        try {
            // 上游格式 → IR → 客户端格式
            JsonNode ir = upstreamConv.responseToIR(body);
            return clientConv.responseFromIR(ir);
        } catch (Exception e) {
            log.warn("Hub-and-Spoke response translation failed for {}→{}, passing through: {}",
                    from, to, e.getMessage());
            return body;
        }
    }

    // ========================
    // 辅助方法
    // ========================

    /**
     * 将 {@link Platform} 枚举映射为 Converter 的 formatId。
     * <p>
     * 映射关系：ANTHROPIC → "messages"、OPENAI → "chat_completions"、
     * OPENAI_RESPONSES → "responses"。未支持的平台返回 null（透传模式）。
     */
    private static String platformToFormatId(Platform platform) {
        return switch (platform) {
            case ANTHROPIC -> "messages";
            case OPENAI -> "chat_completions";
            case OPENAI_RESPONSES -> "responses";
            default -> null;
        };
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

    /**
     * 将 Anthropic 非流式响应转换为 OpenAI Chat Completions 响应。
     */
    private String anthropicToOpenAIResponse(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            // 基础字段
            dst.put("id", src.has("id") ? src.get("id").asText() : "chatcmpl-" + UUID.randomUUID());
            dst.put("object", "chat.completion");
            dst.put("model", src.has("model") ? src.get("model").asText() : "unknown");
            dst.put("created", System.currentTimeMillis() / 1000);

            // 从 content 数组提取纯文本
            String messageContent = "";
            if (src.has("content") && src.get("content").isArray()) {
                messageContent = extractTextFromContentBlocks(src.get("content"));
            }

            // stop_reason → finish_reason（反向映射）
            String finishReason = "stop";
            if (src.has("stop_reason")) {
                finishReason = mapStopReasonToFinishReason(src.get("stop_reason").asText());
            }

            // choices 数组
            ArrayNode choices = JSON.createArrayNode();
            ObjectNode choice = JSON.createObjectNode();
            choice.put("index", 0);
            ObjectNode message = JSON.createObjectNode();
            message.put("role", "assistant");
            message.put("content", messageContent != null ? messageContent : "");
            choice.set("message", message);
            choice.put("finish_reason", finishReason);
            choices.add(choice);
            dst.set("choices", choices);

            // usage 字段重命名
            if (src.has("usage")) {
                JsonNode usage = src.get("usage");
                ObjectNode openAIUsage = JSON.createObjectNode();
                if (usage.has("input_tokens")) {
                    openAIUsage.put("prompt_tokens", usage.get("input_tokens").asInt());
                }
                if (usage.has("output_tokens")) {
                    openAIUsage.put("completion_tokens", usage.get("output_tokens").asInt());
                }
                if (usage.has("total_tokens")) {
                    openAIUsage.put("total_tokens", usage.get("total_tokens").asInt());
                } else {
                    int total = (usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0)
                            + (usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0);
                    openAIUsage.put("total_tokens", total);
                }
                dst.set("usage", openAIUsage);
            }

            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Failed to translate Anthropic→OpenAI response, passing through: {}", e.getMessage());
            return body;
        }
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

    // ========================
    // 非流式响应翻译：Chat Completions ↔ Responses API
    // ========================

    /**
     * 将 Chat Completions 非流式响应转换为 Responses API 格式。
     * <p>
     * {@code choices[0].message.content} → {@code output[0].content[{type:"output_text",text}]}，
     * {@code usage.prompt_tokens} → {@code usage.input_tokens}，{@code completion_tokens} → {@code output_tokens}。
     */
    private String chatCompletionsToResponsesResponse(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            dst.put("id", src.has("id") ? src.get("id").asText() : "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            dst.put("object", "response");
            dst.put("model", src.has("model") ? src.get("model").asText() : "unknown");
            dst.put("created_at", System.currentTimeMillis() / 1000);
            dst.put("status", "completed");

            // choices[0] → output[0]
            ArrayNode output = JSON.createArrayNode();
            if (src.has("choices") && src.get("choices").isArray() && src.get("choices").size() > 0) {
                JsonNode choice = src.get("choices").get(0);
                ObjectNode outputItem = JSON.createObjectNode();
                outputItem.put("type", "message");
                outputItem.put("role", "assistant");
                outputItem.put("id", "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));

                // finish_reason → status
                if (choice.has("finish_reason")) {
                    outputItem.put("status", choice.get("finish_reason").asText());
                }

                // message.content → content[{type:"output_text", text}]
                ArrayNode content = JSON.createArrayNode();
                if (choice.has("message") && choice.get("message").has("content")) {
                    ObjectNode textBlock = JSON.createObjectNode();
                    textBlock.put("type", "output_text");
                    textBlock.put("text", choice.get("message").get("content").asText());
                    content.add(textBlock);
                }
                outputItem.set("content", content);
                output.add(outputItem);
            }
            dst.set("output", output);

            // usage 字段重命名：prompt_tokens → input_tokens, completion_tokens → output_tokens
            if (src.has("usage")) {
                JsonNode usage = src.get("usage");
                ObjectNode responsesUsage = JSON.createObjectNode();
                if (usage.has("prompt_tokens")) {
                    responsesUsage.put("input_tokens", usage.get("prompt_tokens").asInt());
                }
                if (usage.has("completion_tokens")) {
                    responsesUsage.put("output_tokens", usage.get("completion_tokens").asInt());
                }
                if (usage.has("total_tokens")) {
                    responsesUsage.put("total_tokens", usage.get("total_tokens").asInt());
                } else {
                    int total = (usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : 0)
                            + (usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : 0);
                    responsesUsage.put("total_tokens", total);
                }
                dst.set("usage", responsesUsage);
            }

            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Failed to translate ChatCompletions→Responses response, passing through: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 将 Responses API 非流式响应转换为 Chat Completions 格式。
     */
    private String responsesToChatCompletionsResponse(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            dst.put("id", src.has("id") ? src.get("id").asText() : "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            dst.put("object", "chat.completion");
            dst.put("model", src.has("model") ? src.get("model").asText() : "unknown");
            dst.put("created", System.currentTimeMillis() / 1000);

            // output[0] → choices[0]
            ArrayNode choices = JSON.createArrayNode();
            ObjectNode choice = JSON.createObjectNode();
            choice.put("index", 0);
            ObjectNode message = JSON.createObjectNode();
            message.put("role", "assistant");

            String finishReason = "stop";
            if (src.has("output") && src.get("output").isArray() && src.get("output").size() > 0) {
                JsonNode outputItem = src.get("output").get(0);
                if (outputItem.has("role")) {
                    message.put("role", outputItem.get("role").asText());
                }
                if (outputItem.has("status")) {
                    finishReason = outputItem.get("status").asText();
                }
                // content[{type:"output_text", text}] → 提取纯文本
                String text = extractTextOrStringContent(outputItem);
                message.put("content", text != null ? text : "");
            } else {
                message.put("content", "");
            }

            choice.set("message", message);
            choice.put("finish_reason", finishReason);
            choices.add(choice);
            dst.set("choices", choices);

            // usage 字段重命名：input_tokens → prompt_tokens, output_tokens → completion_tokens
            if (src.has("usage")) {
                JsonNode usage = src.get("usage");
                ObjectNode openAIUsage = JSON.createObjectNode();
                if (usage.has("input_tokens")) {
                    openAIUsage.put("prompt_tokens", usage.get("input_tokens").asInt());
                }
                if (usage.has("output_tokens")) {
                    openAIUsage.put("completion_tokens", usage.get("output_tokens").asInt());
                }
                if (usage.has("total_tokens")) {
                    openAIUsage.put("total_tokens", usage.get("total_tokens").asInt());
                } else {
                    int total = (usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0)
                            + (usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0);
                    openAIUsage.put("total_tokens", total);
                }
                dst.set("usage", openAIUsage);
            }

            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Failed to translate Responses→ChatCompletions response, passing through: {}", e.getMessage());
            return body;
        }
    }

    // ========================
    // 非流式响应翻译：Anthropic ↔ Responses API
    // ========================

    /**
     * 将 Anthropic 非流式响应转换为 Responses API 格式。
     * <p>
     * {@code content[{type:"text"}]} → {@code output[0].content[{type:"output_text",text}]}，
     * {@code stop_reason} → {@code output[0].status}，保留 usage 字段。
     */
    private String anthropicToResponsesResponse(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            dst.put("id", src.has("id") ? src.get("id").asText() : "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            dst.put("object", "response");
            dst.put("model", src.has("model") ? src.get("model").asText() : "unknown");
            dst.put("created_at", System.currentTimeMillis() / 1000);
            dst.put("status", "completed");

            // content 数组 → output[0].content
            ArrayNode output = JSON.createArrayNode();
            ObjectNode outputItem = JSON.createObjectNode();
            outputItem.put("type", "message");
            outputItem.put("role", "assistant");
            outputItem.put("id", "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));

            // stop_reason → status
            if (src.has("stop_reason")) {
                outputItem.put("status", src.get("stop_reason").asText());
            }

            // content[{type:"text", text}] → content[{type:"output_text", text}]
            ArrayNode content = JSON.createArrayNode();
            if (src.has("content") && src.get("content").isArray()) {
                for (JsonNode block : src.get("content")) {
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

            // usage：Anthropic input_tokens/output_tokens 字段名与 Responses API 一致，直接透传
            if (src.has("usage")) {
                JsonNode usage = src.get("usage");
                ObjectNode responsesUsage = JSON.createObjectNode();
                if (usage.has("input_tokens")) {
                    responsesUsage.put("input_tokens", usage.get("input_tokens").asInt());
                }
                if (usage.has("output_tokens")) {
                    responsesUsage.put("output_tokens", usage.get("output_tokens").asInt());
                }
                if (usage.has("total_tokens")) {
                    responsesUsage.put("total_tokens", usage.get("total_tokens").asInt());
                } else {
                    int total = (usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0)
                            + (usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0);
                    responsesUsage.put("total_tokens", total);
                }
                dst.set("usage", responsesUsage);
            }

            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Failed to translate Anthropic→Responses response, passing through: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 将 Responses API 非流式响应转换为 Anthropic 格式。
     * <p>
     * {@code output[0].content[{type:"output_text"}]} → {@code content[{type:"text"}]}，
     * {@code output[0].status} → {@code stop_reason}。
     */
    private String responsesToAnthropicResponse(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            dst.put("id", src.has("id") ? src.get("id").asText() : "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            dst.put("type", "message");
            dst.put("role", "assistant");
            dst.put("model", src.has("model") ? src.get("model").asText() : "unknown");

            // output[0] → content + stop_reason
            String stopReason = "end_turn";
            ArrayNode content = JSON.createArrayNode();
            if (src.has("output") && src.get("output").isArray() && src.get("output").size() > 0) {
                JsonNode outputItem = src.get("output").get(0);
                if (outputItem.has("status")) {
                    String status = outputItem.get("status").asText();
                    stopReason = switch (status) {
                        case "completed" -> "end_turn";
                        case "incomplete" -> "max_tokens";
                        default -> status;
                    };
                }
                // content[{type:"output_text", text}] → content[{type:"text", text}]
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

            // usage：字段名一致，直接透传
            if (src.has("usage")) {
                JsonNode usage = src.get("usage");
                ObjectNode anthropicUsage = JSON.createObjectNode();
                if (usage.has("input_tokens")) {
                    anthropicUsage.put("input_tokens", usage.get("input_tokens").asInt());
                }
                if (usage.has("output_tokens")) {
                    anthropicUsage.put("output_tokens", usage.get("output_tokens").asInt());
                }
                if (usage.has("total_tokens")) {
                    anthropicUsage.put("total_tokens", usage.get("total_tokens").asInt());
                } else {
                    int total = (usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0)
                            + (usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0);
                    anthropicUsage.put("total_tokens", total);
                }
                dst.set("usage", anthropicUsage);
            }

            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Failed to translate Responses→Anthropic response, passing through: {}", e.getMessage());
            return body;
        }
    }

    // ========================
    // 流式响应翻译
    // ========================

    /**
     * OpenAI SSE → Anthropic SSE 流式翻译器（状态机）。
     * <p>
     * 用法：每收到上游一行原始 SSE 就调用 {@link #feed(String)}（含 "data: " 前缀），
     * 返回需要写入客户端的 Anthropic SSE 行列表（可能为 0~N 行）。
     */
    public static class OpenAIToAnthropicStreamTranslator {

        private enum State { INIT, BLOCK_STARTED, STREAMING, DONE }

        private State state = State.INIT;
        private final String model;
        private final String messageId;
        private int completionTokens = 0;
        private int inputTokens = 0;
        private String stopReason = "end_turn";

        public OpenAIToAnthropicStreamTranslator(String model) {
            this.model = model;
            this.messageId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }

        /**
         * 消费一行上游原始 SSE 行（含 "data: " 前缀）。
         *
         * @param line 原始 SSE 行（如 "data: {...}" 或 "data: [DONE]"）
         * @return 需要写入客户端的 Anthropic SSE 行列表（含 "event:" / "data:" 前缀）
         */
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (state == State.DONE || line == null || line.isBlank()) {
                return output;
            }

            // 处理 [DONE] 标记
            if ("data: [DONE]".equals(line)) {
                state = State.DONE;
                return output;
            }

            // 只处理 data: 行
            if (!line.startsWith("data: ")) {
                return output;
            }

            String jsonLine = line.substring(6);

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
                    if (u.has("prompt_tokens")) {
                        inputTokens = u.get("prompt_tokens").asInt();
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

        /** @return 从 SSE 流中提取的 input_tokens 数量 */
        public int getInputTokens() {
            return inputTokens;
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

    /**
     * Anthropic SSE → OpenAI SSE 流式翻译器（状态机）。
     * <p>
     * 上游 Anthropic 返回 event:/data: 成对事件，翻译为 OpenAI SSE data: 行。
     * 用法：每收到上游一行原始 SSE 就调用 {@link #feed(String)}，
     * 返回需要写入客户端的 OpenAI SSE 行列表。
     */
    public static class AnthropicToOpenAIStreamTranslator {

        private enum State { INIT, STREAMING, DONE }

        private State state = State.INIT;
        private String currentEvent = null;
        private final String completionId;
        private String model = "unknown";
        private long created = System.currentTimeMillis() / 1000;
        private int inputTokens = 0;
        private int outputTokens = 0;
        private String stopReason = "stop";

        public AnthropicToOpenAIStreamTranslator() {
            this.completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }

        /**
         * 消费一行上游原始 SSE 行（可能是 "event: xxx"、"data: {...}" 或空行）。
         *
         * @param line 上游原始 SSE 行
         * @return 需要写入客户端的 OpenAI SSE data: 行列表（含 "data: " 前缀）
         */
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (state == State.DONE || line == null || line.isBlank()) {
                return output;
            }

            // 记住当前事件类型
            if (line.startsWith("event: ")) {
                currentEvent = line.substring(7).trim();
                return output;
            }

            // 只处理 data: 行
            if (!line.startsWith("data: ")) {
                return output;
            }

            String json = line.substring(6);

            try {
                JsonNode root = JSON.readTree(json);
                String type = root.has("type") ? root.get("type").asText() : null;
                if (type == null) return output;

                switch (type) {
                    case "message_start" -> {
                        // 记录元数据，不输出 chunk
                        if (root.has("message")) {
                            JsonNode msg = root.get("message");
                            if (msg.has("model")) {
                                model = msg.get("model").asText();
                            }
                            if (msg.has("usage")) {
                                JsonNode usage = msg.get("usage");
                                if (usage.has("input_tokens")) {
                                    inputTokens = usage.get("input_tokens").asInt();
                                }
                            }
                        }
                    }
                    case "content_block_start" -> {
                        // 第一个 content block → 发送 role chunk
                        if (state == State.INIT) {
                            output.add("data: " + formatChunk(null, "assistant", null));
                            state = State.STREAMING;
                        }
                    }
                    case "content_block_delta" -> {
                        // delta 文本 → 发送 content chunk
                        if (root.has("delta") && root.get("delta").has("text")) {
                            String text = root.get("delta").get("text").asText();
                            if (!text.isEmpty()) {
                                output.add("data: " + formatChunk(null, null, text));
                            }
                        }
                    }
                    case "content_block_stop" -> {
                        // 不单独输出
                    }
                    case "message_delta" -> {
                        // 记录 stop_reason 和 output_tokens
                        if (root.has("delta") && root.get("delta").has("stop_reason")) {
                            stopReason = mapStopReasonToFinishReason(
                                    root.get("delta").get("stop_reason").asText());
                        }
                        if (root.has("usage") && root.get("usage").has("output_tokens")) {
                            outputTokens = root.get("usage").get("output_tokens").asInt();
                        }
                    }
                    case "message_stop" -> {
                        // 发送最后一个 chunk（含 finish_reason + usage），然后发送 [DONE]
                        output.add("data: " + formatFinalChunk());
                        output.add("data: [DONE]");
                        state = State.DONE;
                    }
                }
            } catch (Exception e) {
                log.debug("Anthropic→OpenAI SSE translation error, skipping line: {}", e.getMessage());
            }
            return output;
        }

        /** @return 是否已完成（发送了 [DONE]） */
        public boolean isDone() {
            return state == State.DONE;
        }

        /** @return 从 Anthropic SSE 事件中提取的 input_tokens */
        public int getInputTokens() {
            return inputTokens;
        }

        /** @return 从 Anthropic SSE 事件中提取的 output_tokens */
        public int getOutputTokens() {
            return outputTokens;
        }

        /** @return 翻译过程中提取的模型名 */
        public String getModel() {
            return model;
        }

        // ---- OpenAI SSE chunk 格式化 ----

        /**
         * 格式化一个 OpenAI SSE chunk。
         *
         * @param finishReason finish_reason（通常只有最后一个 chunk 才有）
         * @param role         delta.role（仅第一个 chunk）
         * @param content      delta.content（文本 chunk）
         */
        private String formatChunk(String finishReason, String role, String content) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"id\":\"").append(completionId).append("\"");
            sb.append(",\"object\":\"chat.completion.chunk\"");
            sb.append(",\"created\":").append(created);
            sb.append(",\"model\":\"").append(escapeJsonValue(model)).append("\"");
            sb.append(",\"choices\":[{\"index\":0,\"delta\":{");

            boolean hasDelta = false;
            if (role != null) {
                sb.append("\"role\":\"").append(escapeJsonValue(role)).append("\"");
                hasDelta = true;
            }
            if (content != null) {
                if (hasDelta) sb.append(",");
                sb.append("\"content\":\"").append(escapeJsonValue(content)).append("\"");
                hasDelta = true;
            }

            sb.append("},\"finish_reason\":");
            if (finishReason != null) {
                sb.append("\"").append(finishReason).append("\"");
            } else {
                sb.append("null");
            }
            sb.append("}]}");
            return sb.toString();
        }

        /** 格式化最后一个 chunk（含 finish_reason + usage） */
        private String formatFinalChunk() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"id\":\"").append(completionId).append("\"");
            sb.append(",\"object\":\"chat.completion.chunk\"");
            sb.append(",\"created\":").append(created);
            sb.append(",\"model\":\"").append(escapeJsonValue(model)).append("\"");
            sb.append(",\"choices\":[{\"index\":0,\"delta\":{}");
            sb.append(",\"finish_reason\":\"").append(stopReason).append("\"}]");
            sb.append(",\"usage\":{");
            sb.append("\"prompt_tokens\":").append(inputTokens);
            sb.append(",\"completion_tokens\":").append(outputTokens);
            sb.append(",\"total_tokens\":").append(inputTokens + outputTokens);
            sb.append("}}");
            return sb.toString();
        }

        /** 转义 JSON 字符串值中的特殊字符 */
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

    // ========================
    // 流式翻译器：Chat Completions SSE → Responses SSE
    // ========================

    /**
     * Chat Completions SSE → Responses SSE 流式翻译器（状态机）。
     * <p>
     * 将上游 OpenAI Chat Completions 的 SSE 流翻译为 Responses API SSE 格式。
     * 严格按照 OpenAI Responses API 流式事件规范：
     * <ol>
     *   <li>{@code response.created}</li>
     *   <li>{@code response.in_progress}（可选）</li>
     *   <li>{@code response.output_item.added}</li>
     *   <li>{@code response.content_part.added}</li>
     *   <li>{@code response.output_text.delta}（多次）</li>
     *   <li>{@code response.text.done}</li>
     *   <li>{@code response.content_part.done}</li>
     *   <li>{@code response.output_item.done}</li>
     *   <li>{@code response.completed}</li>
     * </ol>
     * <p>
     * 用法：每收到上游一行原始 SSE 就调用 {@link #feed(String)}，
     * 返回需要写入客户端的 Responses SSE 行列表（含 "event:" / "data:" 前缀）。
     */
    public static class ChatToResponsesStreamTranslator {

        private enum State {
            /** 等待第一个 chunk（delta.role） */
            INIT,
            /** 已发送 response.created / in_progress / output_item.added / content_part.added，等待内容 */
            STREAMING,
            /** 已发送结束事件 */
            DONE
        }

        private State state = State.INIT;
        private final String responseId;
        private final String itemId;
        private final String model;
        private final long createdAt;
        private long sequenceNumber = 0;
        private int inputTokens = 0;
        private int outputTokens = 0;
        private String finishReason = "stop";
        private String assistantContent = "";

        public ChatToResponsesStreamTranslator(String model) {
            this.responseId = "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            this.itemId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            this.model = model;
            this.createdAt = System.currentTimeMillis() / 1000;
        }

        /**
         * 消费一行上游原始 SSE 行（含 "data: " 前缀）。
         *
         * @param line 原始 SSE 行（如 "data: {...}" 或 "data: [DONE]"）
         * @return 需要写入客户端的 Responses SSE 行列表
         */
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (state == State.DONE || line == null || line.isBlank()) {
                return output;
            }

            // [DONE] 标记 → 发送剩余的结束事件
            if ("data: [DONE]".equals(line)) {
                sendCompletionEvents(output);
                state = State.DONE;
                return output;
            }

            if (!line.startsWith("data: ")) {
                return output;
            }

            String jsonLine = line.substring(6);

            try {
                JsonNode chunk = JSON.readTree(jsonLine);
                if (!chunk.has("choices") || chunk.get("choices").size() == 0) {
                    return output;
                }
                JsonNode choice = chunk.get("choices").get(0);

                // 捕获 usage（可能出现在最后一个 chunk 中）
                if (chunk.has("usage")) {
                    JsonNode u = chunk.get("usage");
                    if (u.has("prompt_tokens")) inputTokens = u.get("prompt_tokens").asInt();
                    if (u.has("completion_tokens")) outputTokens = u.get("completion_tokens").asInt();
                }

                boolean hasFinish = choice.has("finish_reason")
                        && !choice.get("finish_reason").isNull()
                        && !"null".equals(choice.get("finish_reason").asText());

                if (hasFinish) {
                    finishReason = choice.get("finish_reason").asText();
                }

                JsonNode delta = choice.has("delta") ? choice.get("delta") : null;
                if (delta == null && !hasFinish) return output;

                switch (state) {
                    case INIT -> {
                        if (delta != null && delta.has("role")) {
                            // 发送初始事件序列：created → in_progress → output_item.added → content_part.added
                            sendInitialEvents(output);
                            state = State.STREAMING;
                        }
                        // 如果第一个 chunk 同时有 role 和 content，一并处理
                        if (state == State.STREAMING && delta != null && delta.has("content")) {
                            String text = delta.get("content").asText();
                            if (!text.isEmpty()) {
                                assistantContent += text;
                                appendDeltaEvent(output, text);
                            }
                        }
                    }
                    case STREAMING -> {
                        if (delta != null && delta.has("content")) {
                            String text = delta.get("content").asText();
                            if (!text.isEmpty()) {
                                assistantContent += text;
                                appendDeltaEvent(output, text);
                            }
                        }
                    }
                }

                // 检测最后一个有内容的 chunk（有 finish_reason）→ 发送剩余结束事件
                if (hasFinish && state == State.STREAMING) {
                    sendCompletionEvents(output);
                    state = State.DONE;
                }
            } catch (Exception e) {
                log.debug("Chat→Responses SSE translation error, skipping line: {}", e.getMessage());
            }
            return output;
        }

        // ---- 事件批次发送 ----

        /** 发送初始事件序列：response.created → response.in_progress → response.output_item.added → response.content_part.added */
        private void sendInitialEvents(List<String> output) {
            // 1. response.created
            output.add("event: response.created");
            output.add("data: " + formatResponseCreated());
            output.add("");
            // 2. response.in_progress
            output.add("event: response.in_progress");
            output.add("data: " + formatResponseInProgress());
            output.add("");
            // 3. response.output_item.added
            output.add("event: response.output_item.added");
            output.add("data: " + formatOutputItemAdded());
            output.add("");
            // 4. response.content_part.added
            output.add("event: response.content_part.added");
            output.add("data: " + formatContentPartAdded());
            output.add("");
        }

        /** 追加一个 response.output_text.delta 事件行 */
        private void appendDeltaEvent(List<String> output, String text) {
            output.add("event: response.output_text.delta");
            output.add("data: " + formatOutputTextDelta(text));
            output.add("");
        }

        /** 发送结束事件序列：response.text.done → response.content_part.done → response.output_item.done → response.completed */
        private void sendCompletionEvents(List<String> output) {
            // 5. response.text.done
            output.add("event: response.text.done");
            output.add("data: " + formatTextDone());
            output.add("");
            // 6. response.content_part.done
            output.add("event: response.content_part.done");
            output.add("data: " + formatContentPartDone());
            output.add("");
            // 7. response.output_item.done
            output.add("event: response.output_item.done");
            output.add("data: " + formatOutputItemDone());
            output.add("");
            // 8. response.completed
            output.add("event: response.completed");
            output.add("data: " + formatResponseCompleted());
            output.add("");
        }

        /** @return 是否已完成 */
        public boolean isDone() {
            return state == State.DONE;
        }

        /** @return 从 SSE 流中提取的 input_tokens */
        public int getInputTokens() { return inputTokens; }

        /** @return 从 SSE 流中提取的 output_tokens */
        public int getOutputTokens() { return outputTokens; }

        // ---- Responses SSE 事件格式化 ----

        private String formatResponseCreated() {
            long sn = sequenceNumber++;
            return escapeJson(String.format(
                    "{\"type\":\"response.created\",\"sequence_number\":%d,\"response\":{\"id\":\"%s\",\"object\":\"response\",\"model\":\"%s\",\"created_at\":%d,\"status\":\"in_progress\",\"output\":[],\"usage\":null}}",
                    sn, responseId, escapeJsonValue(model), createdAt));
        }

        private String formatResponseInProgress() {
            long sn = sequenceNumber++;
            return escapeJson(String.format(
                    "{\"type\":\"response.in_progress\",\"sequence_number\":%d,\"response\":{\"id\":\"%s\",\"object\":\"response\",\"model\":\"%s\",\"created_at\":%d,\"status\":\"in_progress\",\"output\":[],\"usage\":null}}",
                    sn, responseId, escapeJsonValue(model), createdAt));
        }

        private String formatOutputItemAdded() {
            long sn = sequenceNumber++;
            return escapeJson(String.format(
                    "{\"type\":\"response.output_item.added\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":0,\"item\":{\"id\":\"%s\",\"type\":\"message\",\"status\":\"in_progress\",\"role\":\"assistant\",\"content\":[]}}",
                    sn, responseId, itemId));
        }

        private String formatContentPartAdded() {
            long sn = sequenceNumber++;
            return escapeJson(String.format(
                    "{\"type\":\"response.content_part.added\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"\",\"annotations\":[]}}",
                    sn, responseId, itemId));
        }

        private String formatOutputTextDelta(String text) {
            long sn = sequenceNumber++;
            return escapeJson(String.format(
                    "{\"type\":\"response.output_text.delta\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":0,\"content_index\":0,\"delta\":\"%s\"}",
                    sn, responseId, itemId, escapeJsonValue(text)));
        }

        private String formatTextDone() {
            long sn = sequenceNumber++;
            return escapeJson(String.format(
                    "{\"type\":\"response.text.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":0,\"content_index\":0,\"text\":\"%s\"}",
                    sn, responseId, itemId, escapeJsonValue(assistantContent)));
        }

        private String formatContentPartDone() {
            long sn = sequenceNumber++;
            return escapeJson(String.format(
                    "{\"type\":\"response.content_part.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"%s\"}}",
                    sn, responseId, itemId, escapeJsonValue(assistantContent)));
        }

        private String formatOutputItemDone() {
            long sn = sequenceNumber++;
            String status = mapFinishReasonToResponsesStatus(finishReason);
            return escapeJson(String.format(
                    "{\"type\":\"response.output_item.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":0,\"item\":{\"id\":\"%s\",\"type\":\"message\",\"status\":\"%s\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"%s\"}]}}",
                    sn, responseId, itemId, status, escapeJsonValue(assistantContent)));
        }

        private String formatResponseCompleted() {
            long sn = sequenceNumber++;
            String status = mapFinishReasonToResponsesStatus(finishReason);
            return escapeJson(String.format(
                    "{\"type\":\"response.completed\",\"sequence_number\":%d,\"response\":{\"id\":\"%s\",\"object\":\"response\",\"model\":\"%s\",\"created_at\":%d,\"status\":\"%s\",\"output\":[{\"id\":\"%s\",\"type\":\"message\",\"role\":\"assistant\",\"status\":\"%s\",\"content\":[{\"type\":\"output_text\",\"text\":\"%s\"}]}],\"usage\":{\"input_tokens\":%d,\"output_tokens\":%d,\"total_tokens\":%d}}}",
                    sn, responseId, escapeJsonValue(model), createdAt, status,
                    itemId, status, escapeJsonValue(assistantContent),
                    inputTokens, outputTokens, inputTokens + outputTokens));
        }

        /** Chat Completions finish_reason → Responses API status */
        private static String mapFinishReasonToResponsesStatus(String reason) {
            return switch (reason) {
                case "stop" -> "completed";
                case "length" -> "incomplete";
                case "content_filter" -> "incomplete";
                default -> reason;
            };
        }
    }

    // ========================
    // 流式翻译器：Anthropic SSE → Responses SSE
    // ========================

    /**
     * Anthropic SSE → Responses SSE 流式翻译器（状态机）。
     * <p>
     * 将上游 Anthropic 的 SSE 流翻译为 Responses API SSE 格式。
     * 严格按照 OpenAI Responses API 流式事件规范输出完整事件序列。
     */
    public static class AnthropicToResponsesStreamTranslator {

        private enum State { INIT, STREAMING, DONE }

        private State state = State.INIT;
        private final String responseId;
        private final String itemId;
        private String model = "unknown";
        private final long createdAt;
        private long sequenceNumber = 0;
        private int inputTokens = 0;
        private int outputTokens = 0;
        private String stopReason = "end_turn";
        private String assistantContent = "";

        public AnthropicToResponsesStreamTranslator() {
            this.responseId = "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            this.itemId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            this.createdAt = System.currentTimeMillis() / 1000;
        }

        /**
         * 消费一行上游原始 SSE 行（event: / data: 对）。
         */
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (state == State.DONE || line == null || line.isBlank()) {
                return output;
            }

            if (!line.startsWith("data: ")) {
                return output;
            }

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
                        // 发送初始事件序列
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
                log.debug("Anthropic→Responses SSE translation error, skipping line: {}", e.getMessage());
            }
            return output;
        }

        public boolean isDone() { return state == State.DONE; }
        public int getInputTokens() { return inputTokens; }
        public int getOutputTokens() { return outputTokens; }

        // ---- 事件发送辅助方法 ----

        private void sendCreatedEvent(List<String> output) {
            output.add("event: response.created");
            output.add("data: " + formatResponseCreated());
            output.add("");
        }

        private void sendInProgressEvent(List<String> output) {
            output.add("event: response.in_progress");
            output.add("data: " + formatResponseInProgress());
            output.add("");
        }

        private void sendOutputItemAdded(List<String> output) {
            output.add("event: response.output_item.added");
            output.add("data: " + formatOutputItemAdded());
            output.add("");
        }

        private void sendContentPartAdded(List<String> output) {
            output.add("event: response.content_part.added");
            output.add("data: " + formatContentPartAdded());
            output.add("");
        }

        private void appendDeltaEvent(List<String> output, String text) {
            output.add("event: response.output_text.delta");
            output.add("data: " + formatOutputTextDelta(text));
            output.add("");
        }

        private void sendTextDoneEvent(List<String> output) {
            output.add("event: response.text.done");
            output.add("data: " + formatTextDone());
            output.add("");
        }

        private void sendContentPartDoneEvent(List<String> output) {
            output.add("event: response.content_part.done");
            output.add("data: " + formatContentPartDone());
            output.add("");
        }

        private void sendOutputItemDoneEvent(List<String> output) {
            output.add("event: response.output_item.done");
            output.add("data: " + formatOutputItemDone());
            output.add("");
        }

        private void sendCompletedEvent(List<String> output) {
            output.add("event: response.completed");
            output.add("data: " + formatResponseCompleted());
            output.add("");
        }

        // ---- JSON 格式化 ----

        private String formatResponseCreated() {
            long sn = sequenceNumber++;
            return escapeJson(String.format(
                    "{\"type\":\"response.created\",\"sequence_number\":%d,\"response\":{\"id\":\"%s\",\"object\":\"response\",\"model\":\"%s\",\"created_at\":%d,\"status\":\"in_progress\",\"output\":[],\"usage\":null}}",
                    sn, responseId, escapeJsonValue(model), createdAt));
        }

        private String formatResponseInProgress() {
            long sn = sequenceNumber++;
            return escapeJson(String.format(
                    "{\"type\":\"response.in_progress\",\"sequence_number\":%d,\"response\":{\"id\":\"%s\",\"object\":\"response\",\"model\":\"%s\",\"created_at\":%d,\"status\":\"in_progress\",\"output\":[],\"usage\":null}}",
                    sn, responseId, escapeJsonValue(model), createdAt));
        }

        private String formatOutputItemAdded() {
            long sn = sequenceNumber++;
            return escapeJson(String.format(
                    "{\"type\":\"response.output_item.added\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":0,\"item\":{\"id\":\"%s\",\"type\":\"message\",\"status\":\"in_progress\",\"role\":\"assistant\",\"content\":[]}}",
                    sn, responseId, itemId));
        }

        private String formatContentPartAdded() {
            long sn = sequenceNumber++;
            return escapeJson(String.format(
                    "{\"type\":\"response.content_part.added\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"\",\"annotations\":[]}}",
                    sn, responseId, itemId));
        }

        private String formatOutputTextDelta(String text) {
            long sn = sequenceNumber++;
            return escapeJson(String.format(
                    "{\"type\":\"response.output_text.delta\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":0,\"content_index\":0,\"delta\":\"%s\"}",
                    sn, responseId, itemId, escapeJsonValue(text)));
        }

        private String formatTextDone() {
            long sn = sequenceNumber++;
            return escapeJson(String.format(
                    "{\"type\":\"response.text.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":0,\"content_index\":0,\"text\":\"%s\"}",
                    sn, responseId, itemId, escapeJsonValue(assistantContent)));
        }

        private String formatContentPartDone() {
            long sn = sequenceNumber++;
            return escapeJson(String.format(
                    "{\"type\":\"response.content_part.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"%s\"}}",
                    sn, responseId, itemId, escapeJsonValue(assistantContent)));
        }

        private String formatOutputItemDone() {
            long sn = sequenceNumber++;
            String status = mapAnthropicStopReasonToResponsesStatus(stopReason);
            return escapeJson(String.format(
                    "{\"type\":\"response.output_item.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":0,\"item\":{\"id\":\"%s\",\"type\":\"message\",\"status\":\"%s\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"%s\"}]}}",
                    sn, responseId, itemId, status, escapeJsonValue(assistantContent)));
        }

        private String formatResponseCompleted() {
            long sn = sequenceNumber++;
            String status = mapAnthropicStopReasonToResponsesStatus(stopReason);
            return escapeJson(String.format(
                    "{\"type\":\"response.completed\",\"sequence_number\":%d,\"response\":{\"id\":\"%s\",\"object\":\"response\",\"model\":\"%s\",\"created_at\":%d,\"status\":\"%s\",\"output\":[{\"id\":\"%s\",\"type\":\"message\",\"role\":\"assistant\",\"status\":\"%s\",\"content\":[{\"type\":\"output_text\",\"text\":\"%s\"}]}],\"usage\":{\"input_tokens\":%d,\"output_tokens\":%d,\"total_tokens\":%d}}}",
                    sn, responseId, escapeJsonValue(model), createdAt, status,
                    itemId, status, escapeJsonValue(assistantContent),
                    inputTokens, outputTokens, inputTokens + outputTokens));
        }

        /** Anthropic stop_reason → Responses API status */
        private static String mapAnthropicStopReasonToResponsesStatus(String reason) {
            return switch (reason) {
                case "end_turn" -> "completed";
                case "max_tokens" -> "incomplete";
                default -> reason;
            };
        }
    }

    // ========================
    // 流式翻译器：Responses SSE → Chat Completions SSE
    // ========================

    /**
     * Responses SSE → Chat Completions SSE 流式翻译器（状态机）。
     * <p>
     * 将上游 Responses API 的 SSE 流翻译为 Chat Completions SSE 格式。
     * 上游发送完整事件序列（response.created → output_item.added → content_part.added →
     * output_text.delta → text.done → content_part.done → output_item.done → completed），
     * 本翻译器将其映射回 Chat Completions SSE（delta.role → delta.content → finish_reason + [DONE]）。
     */
    public static class ResponsesToChatStreamTranslator {

        private enum State { INIT, STREAMING, DONE }

        private State state = State.INIT;
        private final String completionId;
        private String model = "unknown";
        private final long created;
        private int inputTokens = 0;
        private int outputTokens = 0;
        private String finishReason = "stop";

        public ResponsesToChatStreamTranslator() {
            this.completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            this.created = System.currentTimeMillis() / 1000;
        }

        /**
         * 消费一行上游原始 SSE 行。
         */
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (state == State.DONE || line == null || line.isBlank()) {
                return output;
            }

            // 跳过 event: 行，只处理 data: 行
            if (!line.startsWith("data: ")) {
                return output;
            }

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
                        // 发送 role chunk（Chat Completions 第一个 chunk）
                        output.add("data: " + formatChunk(null, "assistant", null));
                        state = State.STREAMING;
                    }
                    case "response.output_text.delta" -> {
                        if (root.has("delta")) {
                            String text = root.get("delta").asText();
                            if (!text.isEmpty()) {
                                output.add("data: " + formatChunk(null, null, text));
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
                            // 从 output[0].status 提取 finish_reason
                            if (resp.has("output") && resp.get("output").isArray() && resp.get("output").size() > 0) {
                                JsonNode outItem = resp.get("output").get(0);
                                if (outItem.has("status")) {
                                    finishReason = mapResponsesStatusToFinishReason(outItem.get("status").asText());
                                }
                            }
                        }
                        output.add("data: " + formatFinalChunk());
                        output.add("data: [DONE]");
                        state = State.DONE;
                    }
                }
            } catch (Exception e) {
                log.debug("Responses→Chat SSE translation error, skipping line: {}", e.getMessage());
            }
            return output;
        }

        public boolean isDone() { return state == State.DONE; }
        public int getInputTokens() { return inputTokens; }
        public int getOutputTokens() { return outputTokens; }

        /** Responses API status → Chat Completions finish_reason */
        private static String mapResponsesStatusToFinishReason(String status) {
            return switch (status) {
                case "completed" -> "stop";
                case "incomplete" -> "length";
                default -> status;
            };
        }

        private String formatChunk(String finishReasonParam, String role, String content) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"id\":\"").append(completionId).append("\"");
            sb.append(",\"object\":\"chat.completion.chunk\"");
            sb.append(",\"created\":").append(created);
            sb.append(",\"model\":\"").append(escapeJsonValue(model)).append("\"");
            sb.append(",\"choices\":[{\"index\":0,\"delta\":{");

            boolean hasDelta = false;
            if (role != null) {
                sb.append("\"role\":\"").append(escapeJsonValue(role)).append("\"");
                hasDelta = true;
            }
            if (content != null) {
                if (hasDelta) sb.append(",");
                sb.append("\"content\":\"").append(escapeJsonValue(content)).append("\"");
                hasDelta = true;
            }

            sb.append("},\"finish_reason\":");
            if (finishReasonParam != null) {
                sb.append("\"").append(finishReasonParam).append("\"");
            } else {
                sb.append("null");
            }
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
            sb.append(",\"finish_reason\":\"").append(finishReason).append("\"}]");
            sb.append(",\"usage\":{");
            sb.append("\"prompt_tokens\":").append(inputTokens);
            sb.append(",\"completion_tokens\":").append(outputTokens);
            sb.append(",\"total_tokens\":").append(inputTokens + outputTokens);
            sb.append("}}");
            return sb.toString();
        }
    }

    // ========================
    // 流式翻译器：Responses SSE → Anthropic SSE
    // ========================

    /**
     * Responses SSE → Anthropic SSE 流式翻译器（状态机）。
     * <p>
     * 将上游 Responses API 的 SSE 流翻译为 Anthropic SSE 格式。
     * 上游发送完整事件序列（response.created → output_item.added → content_part.added →
     * output_text.delta → text.done → content_part.done → output_item.done → completed），
     * 本翻译器将其映射回 Anthropic SSE（message_start → content_block_start → content_block_delta →
     * content_block_stop → message_delta → message_stop）。
     */
    public static class ResponsesToAnthropicStreamTranslator {

        private enum State { INIT, BLOCK_STARTED, STREAMING, DONE }

        private State state = State.INIT;
        private final String messageId;
        private String model = "unknown";
        private int inputTokens = 0;
        private int outputTokens = 0;
        private String stopReason = "end_turn";

        public ResponsesToAnthropicStreamTranslator() {
            this.messageId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }

        /**
         * 消费一行上游原始 SSE 行。
         */
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (state == State.DONE || line == null || line.isBlank()) {
                return output;
            }

            if (!line.startsWith("data: ")) {
                return output;
            }

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
                        // 发送 message_start + content_block_start
                        output.add("event: message_start");
                        output.add("data: " + formatMessageStart());
                        output.add("");
                        output.add("event: content_block_start");
                        output.add("data: " + formatContentBlockStart(0));
                        output.add("");
                        state = State.BLOCK_STARTED;
                    }
                    // 跳过中间结构事件（output_item.added, content_part.added 等），
                    // 只在 output_text.delta 时发送 content_block_delta
                    case "response.in_progress", "response.output_item.added", "response.content_part.added" -> {
                        // 无需输出，Anthropic 在 message_start 时已发送 item 结构
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
                    // text.done / content_part.done / output_item.done → 忽视，统一等 response.completed
                    case "response.text.done", "response.content_part.done", "response.output_item.done" -> {
                        // 无需输出，等 response.completed 统一结束
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
                        // 发送结束事件序列
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
                log.debug("Responses→Anthropic SSE translation error, skipping line: {}", e.getMessage());
            }
            return output;
        }

        public boolean isDone() { return state == State.DONE; }
        public int getInputTokens() { return inputTokens; }
        public int getOutputTokens() { return outputTokens; }

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
                    stopReason, outputTokens));
        }

        private String formatMessageStop() {
            return escapeJson("{\"type\":\"message_stop\"}");
        }
    }

    // ---- 工具方法 ----

    /** 对完整的 SSE data 行做 JSON 转义（当前为恒等函数，JSON 已在内格式化） */
    private static String escapeJson(String s) {
        return s;
    }

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

    private static void copyIfExists(JsonNode src, ObjectNode dst, String field) {
        if (src.has(field) && !src.get(field).isNull()) {
            dst.set(field, src.get(field));
        }
    }
}
