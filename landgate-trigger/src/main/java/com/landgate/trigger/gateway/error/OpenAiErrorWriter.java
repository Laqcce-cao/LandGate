package com.landgate.trigger.gateway.error;

import com.landgate.trigger.gateway.error.IErrorWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * OpenAI 错误响应写入器 —— 使用 OpenAI 原生 JSON 错误格式。
 * <pre>
 * {"error":{"message":"&lt;message&gt;","type":"&lt;code&gt;","param":null,"code":null}}
 * </pre>
 */
@Component
public class OpenAiErrorWriter implements IErrorWriter {

    @Override
    public void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        String json = String.format(
                "{\"error\":{\"message\":\"%s\",\"type\":\"%s\",\"param\":null,\"code\":null}}",
                escapeJson(message), escapeJson(code));
        response.getWriter().write(json);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
