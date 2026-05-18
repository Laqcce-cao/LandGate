package com.landgate.api.oauth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * OAuth 回调请求 —— 前端在收到上游平台的回调后，将 authorization_code 和 state 提交给后端。
 * <p>
 * 后端验证 state（从 Redis 读取并校验），然后用 code 向上游平台换取 access_token。
 *
 * @param code  上游平台返回的 authorization_code（一次性使用）
 * @param state 授权流程标识，需与 {@link OAuthAuthorizeResponse#state} 一致
 */
public record OAuthCallbackRequest(
        @NotBlank String code,
        @NotBlank String state
) {}
