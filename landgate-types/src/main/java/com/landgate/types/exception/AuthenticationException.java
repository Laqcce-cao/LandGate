package com.landgate.types.exception;

/**
 * 认证异常 —— 凭证无效、令牌过期等。
 */
public class AuthenticationException extends BusinessException {

    public AuthenticationException(String message) {
        super("AUTH_ERROR", message, 401);
    }
}
