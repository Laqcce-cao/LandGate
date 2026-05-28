package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OpenAI Chat Completions API ↔ IR（OpenAI Responses API 格式）转换器。
 * <p>
 * 门面模式：实现 {@link ProtocolConverter} 接口，内部委托给两个独立转换器：
 * <ul>
 *   <li>{@link ChatCompletionsToResponsesConverter} — Chat Completions → IR 方向</li>
 *   <li>{@link ResponsesToChatCompletionsConverter} — IR → Chat Completions 方向</li>
 * </ul>
 * <p>
 * 参照：sub2api {@code chatcompletions_to_responses.go} + {@code responses_to_chatcompletions.go}
 */
@Slf4j
@Component
public class ChatCompletionsConverter implements ProtocolConverter {

    private final ChatCompletionsToResponsesConverter toIR;
    private final ResponsesToChatCompletionsConverter fromIR;

    public ChatCompletionsConverter() {
        this.toIR = new ChatCompletionsToResponsesConverter();
        this.fromIR = new ResponsesToChatCompletionsConverter();
    }

    @Override
    public String getFormatId() {
        return "chat_completions";
    }

    // ========================
    // 请求转换
    // ========================

    /**
     * Chat Completions 请求 → IR（Responses 格式）。
     */
    @Override
    public JsonNode requestToIR(String body) {
        return toIR.requestToIR(body);
    }

    /**
     * IR（Responses 格式）→ Chat Completions 请求。
     */
    @Override
    public String requestFromIR(JsonNode ir) {
        return fromIR.requestFromIR(ir);
    }

    // ========================
    // 非流式响应转换
    // ========================

    /**
     * Chat Completions 响应 → IR（Responses 格式）。
     */
    @Override
    public JsonNode responseToIR(String body) {
        return toIR.responseToIR(body);
    }

    /**
     * IR（Responses 格式）→ Chat Completions 响应。
     */
    @Override
    public String responseFromIR(JsonNode ir) {
        return fromIR.responseFromIR(ir);
    }

    // ========================
    // 流式 SSE 翻译
    // ========================

    /**
     * Chat Completions SSE → IR SSE（Responses 格式）。
     */
    @Override
    public StreamTranslator createStreamToIR(String model) {
        return toIR.createStreamToIR(model);
    }

    /**
     * IR SSE（Responses 格式）→ Chat Completions SSE。
     */
    @Override
    public StreamTranslator createStreamFromIR(String model) {
        return fromIR.createStreamFromIR(model);
    }
}
