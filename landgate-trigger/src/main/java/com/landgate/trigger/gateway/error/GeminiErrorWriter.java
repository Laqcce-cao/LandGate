package com.landgate.trigger.gateway.error;

import com.landgate.trigger.gateway.IErrorWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Gemini 错误响应写入器 —— 使用 Google Gemini 原生 JSON 错误格式。
 * <pre>
 * {"error":{"code":&lt;status&gt;,"message":"&lt;message&gt;","status":"&lt;code&gt;"}}
 * </pre>
 */
@Component
public class GeminiErrorWriter implements IErrorWriter {

    @Override
    public void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        String json = String.format(
                "{\"error\":{\"code\":%d,\"message\":\"%s\",\"status\":\"%s\"}}",
                status, escapeJson(message), escapeJson(code));
        response.getWriter().write(json);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
