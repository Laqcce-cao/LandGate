package com.landgate.api.oauth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * OAuth 授权请求 —— 前端发起 OAuth 账号添加时传入。
 * <p>
 * 触发后端的 PKCE 参数生成和授权 URL 构造，返回 {@link OAuthAuthorizeResponse} 供前端跳转。
 *
 * @param platform    目标平台，如 "anthropic" 或 "openai"
 * @param redirectUri 授权完成后的回调地址（可选，不传则使用默认配置）
 */
public record OAuthAuthorizeRequest(
        @NotBlank String platform,
        String redirectUri
) {}
