package com.landgate.trigger.gateway.error;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 错误响应写入器接口 —— 以平台原生格式向客户端返回错误。
 */
public interface IErrorWriter {

    void writeError(HttpServletResponse response, int status, String code, String message) throws IOException;
}
