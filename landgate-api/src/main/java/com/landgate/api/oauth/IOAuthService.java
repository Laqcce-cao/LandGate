package com.landgate.api.oauth;

import com.landgate.api.oauth.dto.*;

/**
 * OAuth 凭证管理服务接口 —— 定义 OAuth 授权流程和 Token 刷新操作。
 * <p>
 * 支持两种 OAuth 流程：
 * <ul>
 *   <li>Authorization Code Flow + PKCE —— Anthropic</li>
 *   <li>Device Code Flow —— OpenAI</li>
 * </ul>
 */
public interface IOAuthService {

    /**
     * 生成 OAuth 授权 URL（Authorization Code Flow，当前仅 Anthropic 使用）。
     *
     * @param request 授权请求（platform, redirectUri）
     * @return 授权 URL + state 标识
     */
    OAuthAuthorizeResponse authorize(OAuthAuthorizeRequest request);

    /**
     * 处理 OAuth 回调，用 authorization_code 换取 token 并创建 Account。
     *
     * @param request 回调请求（code, state）
     * @return 创建的 Account 信息
     */
    OAuthCallbackResponse callback(OAuthCallbackRequest request);

    /**
     * 手动刷新指定账号的 OAuth Token。
     *
     * @param accountId 账号 ID
     */
    void refreshToken(Long accountId);

    /**
     * 发起 Device Code Flow 授权 —— 向 OpenAI 请求设备码和验证 URL。
     *
     * @param request 设备码请求（platform）
     * @return 设备码信息（device_auth_id, user_code, verification_uri 等）
     */
    DeviceCodeResponse initiateDeviceCode(DeviceCodeRequest request);

    /**
     * 轮询 Device Code Flow 授权状态 —— 检查用户是否已完成授权。
     * 授权成功时自动完成 code→token 交换并创建 Account。
     *
     * @param request 轮询请求（deviceAuthId, userCode）
     * @return 授权状态 + 成功后创建的 Account 信息
     */
    DeviceCodePollResponse pollDeviceCode(DeviceCodePollRequest request);
}
