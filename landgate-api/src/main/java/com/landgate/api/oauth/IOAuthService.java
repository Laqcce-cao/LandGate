package com.landgate.api.oauth;

import com.landgate.api.oauth.dto.*;

/**
 * OAuth 凭证管理服务接口 —— 定义 OAuth 授权流程和 Token 刷新操作。
 */
public interface IOAuthService {

    /**
     * 生成 OAuth 授权 URL。
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
}
