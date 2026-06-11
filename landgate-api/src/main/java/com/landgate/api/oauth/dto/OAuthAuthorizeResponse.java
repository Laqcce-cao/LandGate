package com.landgate.api.oauth.dto;

/**
 * OAuth 授权响应 —— 包含前端需要打开的授权 URL 和用于回调验证的 state。
 *
 * @param authorizeUrl 上游平台的 OAuth 授权页面完整 URL（含 PKCE 参数）
 * @param state        本次授权流程的唯一标识，需在回调时传回以验证请求合法性
 * @param expiresIn    state 的有效期（秒），超时后授权流程失效
 */
public record OAuthAuthorizeResponse(
        String authorizeUrl,
        String state,
        int expiresIn
) {}
