package com.landgate.types.exception;

/**
 * 请求参数错误异常。
 */
public class BadRequestException extends BusinessException {

    public BadRequestException(String message) {
        super("BAD_REQUEST", message, 400);
    }
}
