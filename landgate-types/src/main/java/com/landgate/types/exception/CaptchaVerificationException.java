package com.landgate.types.exception;

/**
 * CAPTCHA 验证异常 —— 人机验证失败时抛出。
 */
public class CaptchaVerificationException extends BusinessException {
    public CaptchaVerificationException(String message) {
        super("CAPTCHA_ERROR", message, 400);
    }
}
