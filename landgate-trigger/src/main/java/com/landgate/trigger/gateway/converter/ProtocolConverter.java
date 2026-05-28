package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 协议格式转换器 —— 一种外部 API 格式与 IR（OpenAI Responses API 格式）之间的双向转换。
 * <p>
 * 外部格式标识（如 "messages"、"chat_completions"、"responses"）通过 {@link #getFormatId()} 获取，
 * 每个 Converter 注册到 {@link ConverterRegistry}，由 Gateway 按 (客户端格式, 上游格式) 查表。
 * <p>
 * 转换分三类：
 * <ol>
 *   <li>请求体转换：客户端 JSON → IR → 上游 JSON</li>
 *   <li>非流式响应转换：上游 JSON → IR → 客户端 JSON</li>
 *   <li>流式 SSE 转换：通过 {@link #createStreamToIR(String)} 和 {@link #createStreamFromIR(String)} 获取状态机</li>
 * </ol>
 */
public interface ProtocolConverter {

    /** 外部格式标识（如 "messages"、"chat_completions"、"responses"） */
    String getFormatId();

    // ========================
    // 请求转换
    // ========================

    /**
     * 将外部格式请求体转为 IR（OpenAI Responses API 格式）。
     *
     * @param body 外部格式的请求 JSON 字符串
     * @return IR 格式的 JsonNode
     */
    JsonNode requestToIR(String body);

    /**
     * 将 IR 格式请求体转为外部格式。
     *
     * @param ir IR 格式的 JsonNode
     * @return 外部格式的请求 JSON 字符串
     */
    String requestFromIR(JsonNode ir);

    // ========================
    // 非流式响应转换
    // ========================

    /**
     * 将外部格式的非流式响应体转为 IR。
     *
     * @param body 外部格式的响应 JSON 字符串
     * @return IR 格式的 JsonNode
     */
    JsonNode responseToIR(String body);

    /**
     * 将 IR 格式的响应转为外部格式。
     *
     * @param ir IR 格式的 JsonNode
     * @return 外部格式的响应 JSON 字符串
     */
    String responseFromIR(JsonNode ir);

    // ========================
    // 流式 SSE 转换
    // ========================

    /**
     * 创建 SSE 流翻译器：将上游格式的原始 SSE 行转换为 IR SSE 事件。
     * <p>
     * 返回的 StreamTranslator 由 Gateway 的 SSE 循环驱动：
     * 每收到一行上游 SSE → {@code feed(line)} → 返回需要写入客户端的翻译后 SSE 行列表。
     *
     * @param model 请求的模型名（用于构造 SSE 事件中的元数据）
     * @return 上游→IR 的流式翻译器
     */
    StreamTranslator createStreamToIR(String model);

    /**
     * 创建 SSE 流翻译器：将 IR SSE 事件转换为外部格式的 SSE 行。
     *
     * @param model 请求的模型名
     * @return IR→外部格式 的流式翻译器
     */
    StreamTranslator createStreamFromIR(String model);
}
