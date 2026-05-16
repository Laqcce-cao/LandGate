package com.landgate.api.auth;

import com.landgate.api.auth.dto.*;

/**
 * 认证服务接口 —— 定义用户注册、登录、API Key 管理的领域契约。
 */
public interface IAuthService {

    AuthResponse register(String email, String password);

    AuthResponse login(String email, String password);

    UserInfo getCurrentUser(Long userId);

    void revokeAllUserTokens(Long userId);

    ApiKeyResponse createApiKey(Long userId, String name, Long groupId);

    void deleteApiKey(Long userId, Long apiKeyId);
}
