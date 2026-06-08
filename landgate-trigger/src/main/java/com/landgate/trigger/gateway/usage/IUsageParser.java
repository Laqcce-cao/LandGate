package com.landgate.trigger.gateway.usage;

import com.landgate.domain.billing.model.valobj.UsageTokens;

/**
 * 用量解析器接口 —— 从上游 AI 提供商响应中提取 Token 用量。
 */
public interface IUsageParser {

    /**
     * 解析 SSE 的 data 载荷。
     * <p>
     * 注意：这里传入的不是完整 SSE 行，而是调用方已移除 {@code "data: "} 前缀后的内容。
     * 例如原始行 {@code data: {"type":"message_delta"}} 会传入 {@code {"type":"message_delta"}}。
     */
    UsageTokens parseSSELine(String sseData);

    /**
     * 解析非流式完整响应体。
     * <p>
     * 响应体格式必须与上游实际响应协议一致，而不是客户端请求协议。
     */
    UsageTokens parseNonStreaming(String responseBody);

    /**
     * 判断完整 SSE 原始行是否表示流结束。
     * <p>
     * 注意：这里传入的是完整 SSE 行，例如 {@code data: [DONE]} 或
     * {@code data: {"type":"response.completed"}}，不是移除 {@code "data: "} 后的载荷。
     */
    boolean isStreamDone(String sseLine);
}
