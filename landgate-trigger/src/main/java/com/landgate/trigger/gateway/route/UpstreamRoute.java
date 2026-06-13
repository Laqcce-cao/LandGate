package com.landgate.trigger.gateway.route;

import com.landgate.types.enums.Platform;

/**
 * 上游路由计划 —— 选中账号后得到的上游端点与协议格式决策。
 * <p>
 * Gateway 后续的协议翻译、请求构建、响应翻译和用量解析都应以本对象为单一事实来源。
 */
public record UpstreamRoute(
        /** 上游账号所属平台。 */
        Platform upstreamPlatform,
        /** 客户端入口格式，如 messages、chat_completions、responses、gemini。 */
        String clientFormat,
        /** 真实上游协议格式。 */
        String upstreamFormat,
        /** 真实上游端点类型。 */
        EndpointKind endpointKind,
        /** 最终上游 URL，不包含动态认证 token。 */
        String targetUrl,
        /** 是否由路由强制流式处理。 */
        boolean forceStreaming,
        /** 是否需要执行 OpenAI Codex OAuth 请求体规范化。 */
        boolean normalizeCodexOAuthBody,
        /** 用量解析器使用的格式。 */
        String usageFormat,
        /** 路由命中原因，用于日志排查。 */
        String reason
) {
    /** ChatGPT Codex /compact 返回普通 JSON，不能被客户端 stream=true 强制走 SSE 分支。 */
    public boolean forceNonStreamingResponse() {
        return isCompactCodexResponsesEndpoint(endpointKind, targetUrl);
    }

    public static boolean isCompactCodexResponsesEndpoint(EndpointKind endpointKind, String targetUrl) {
        return endpointKind == EndpointKind.OPENAI_CODEX_RESPONSES
                && targetUrl != null
                && targetUrl.replaceAll("/+$", "").endsWith("/compact");
    }
}
