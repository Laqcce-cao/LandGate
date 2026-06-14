package com.landgate.trigger.gateway.error;

import com.landgate.types.gateway.GatewayHttpHeaderPolicy;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Anthropic 错误响应写入器 —— 使用 Anthropic 原生 JSON 错误格式。
 * <pre>
 * {"type":"error","error":{"type":"&lt;errorType&gt;","message":"&lt;message&gt;"}}
 * </pre>
 */
@Component
public class AnthropicErrorWriter implements IErrorWriter {

    @Override
    public void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(GatewayHttpHeaderPolicy.MEDIA_TYPE_JSON_UTF8);
        String json = String.format(
                "{\"type\":\"error\",\"error\":{\"type\":\"%s\",\"message\":\"%s\"}}",
                escapeJson(code), escapeJson(message));
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
