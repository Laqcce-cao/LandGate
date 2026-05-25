package com.landgate.trigger.gateway;

import com.landgate.types.enums.Platform;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 网关请求派发器 —— 根据 URL 路径确定平台，选择对应的 {@link IGatewayHandler} 处理请求。
 * <p>
 * URL 路径与平台的关系由 AI 厂商的 API 协议标准决定，不需要从 Group 配置读取：
 * <ul>
 *   <li>{@code /v1/messages} → Anthropic Messages API</li>
 *   <li>{@code /v1/chat/completions} → OpenAI Chat Completions API</li>
 *   <li>{@code /v1beta/models/**} → Gemini API</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayDispatcher {

    private final GatewayHandlerFactory factory;

    /** URL路径前缀 → Platform 映射 */
    private static final Map<String, Platform> PATH_PLATFORM = Map.of(
            "/v1/messages", Platform.ANTHROPIC,
            "/v1/chat/completions", Platform.OPENAI,
            "/v1beta/models/", Platform.GEMINI
    );

    /**
     * 根据请求路径派发到对应的网关处理器。
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @param body     请求体（原始字符串）
     */
    public void dispatch(HttpServletRequest request, HttpServletResponse response, String body) throws IOException {
        String path = request.getServletPath();
        Platform platform = resolvePlatform(path);
        if (platform == null) {
            log.warn("No platform mapping for path: {}", path);
            response.setStatus(404);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Unsupported API path: " + path + "\"}");
            return;
        }
        IGatewayHandler handler = factory.getHandler(platform);
        handler.handle(body, request, response);
    }

    /**
     * 根据请求路径匹配平台。
     * 使用前缀匹配，因为 Gemini 路径包含动态模型名（{@code /v1beta/models/gemini-pro:generateContent}）。
     */
    private Platform resolvePlatform(String path) {
        for (Map.Entry<String, Platform> entry : PATH_PLATFORM.entrySet()) {
            if (path.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
