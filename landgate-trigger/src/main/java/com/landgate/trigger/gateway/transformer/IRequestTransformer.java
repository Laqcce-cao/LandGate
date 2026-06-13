package com.landgate.trigger.gateway.transformer;

import com.landgate.domain.account.model.entity.AccountEntity;

import java.net.http.HttpRequest;

/**
 * 上游请求转换器接口 —— 将客户端请求构建为上游 AI 提供商的 HTTP 请求。
 */
public interface IRequestTransformer {

    HttpRequest buildUpstreamRequest(UpstreamRequestContext context);

    /**
     * Compatibility entry point for older direct callers.
     * Normal gateway traffic should pass an explicit {@link UpstreamRequestContext}.
     */
    default HttpRequest buildUpstreamRequest(String body, AccountEntity account, String accessToken) {
        return buildUpstreamRequest(UpstreamRequestContext.fromLegacy(body, account, accessToken));
    }

    String extractModel(String body);

    boolean isStreamRequest(String body);

    /**
     * 从请求 body 中提取终端用户标识符，用于会话 hash 的细粒度粘滞。
     * <p>
     * Anthropic: body.metadata.user_id; OpenAI: body.user; 无法提取时返回 null。
     *
     * @param body 请求体 JSON 字符串
     * @return 用户标识符，无法提取时返回 null
     */
    default String extractUserId(String body) {
        return null;
    }
}
