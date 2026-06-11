package com.landgate.types.exception;

/**
 * 资源未找到异常。
 */
public class NotFoundException extends BusinessException {

    public NotFoundException(String message) {
        super("NOT_FOUND", message, 404);
    }
}
