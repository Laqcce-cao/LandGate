package com.landgate.trigger.gateway.route;

/**
 * 上游端点类型 —— 表示请求最终发送到的真实上游 API 端点。
 * <p>
 * 与平台和协议格式不同，端点类型精确到具体 URL 语义，便于路由测试、日志排查和后续扩展。
 */
public enum EndpointKind {

    /** Anthropic Messages API。 */
    ANTHROPIC_MESSAGES,
    /** OpenAI Chat Completions API。 */
    OPENAI_CHAT_COMPLETIONS,
    /** OpenAI Responses API。 */
    OPENAI_RESPONSES,
    /** ChatGPT 内部 Codex Responses 端点。 */
    OPENAI_CODEX_RESPONSES
}
