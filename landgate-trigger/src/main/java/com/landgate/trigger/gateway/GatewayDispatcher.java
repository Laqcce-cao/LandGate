package com.landgate.trigger.gateway;

import com.landgate.types.gateway.GatewayClientRoute;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.GatewayHttpHeaderPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 网关请求派发器 —— 根据 URL 路径确定平台和请求格式，选择对应的 {@link IGatewayHandler} 处理请求。
 * <p>
 * URL 路径与平台/格式的关系由 AI 厂商的 API 协议标准决定，不需要从 Group 配置读取：
 * <ul>
 *   <li>{@code /v1/messages} → Anthropic Messages API（platform=ANTHROPIC, format="messages"）</li>
 *   <li>{@code /v1/chat/completions} → OpenAI Chat Completions API（platform=OPENAI, format="chat_completions"）</li>
 *   <li>{@code /v1/responses} → OpenAI Responses API（platform=OPENAI, format="responses"）</li>
 *   <li>{@code /backend-api/codex/responses} → OpenAI Responses API（Codex CLI 兼容，platform=OPENAI, format="responses"）</li>
 * </ul>
 * <p>
 * Phase 2 改动：删除 {@code Platform.OPENAI_RESPONSES} 枚举值后，客户端格式不再通过 platform 区分；
 * 改用独立的 {@link #ATTR_REQUEST_FORMAT} request attribute 单独存储格式 ID，
 * platform 仅表示当前支持的上游服务提供商（ANTHROPIC/OPENAI）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayDispatcher {

    private final GatewayHandlerFactory factory;

    /** request attribute key：客户端请求平台（URL 路径决定） */
    public static final String ATTR_REQUEST_PLATFORM = "gateway_request_platform";

    /**
     * request attribute key：客户端请求格式 ID（URL 路径决定）。
     * <p>
     * 取值为 {@link com.landgate.trigger.gateway.converter.ProtocolConverter#getFormatId()}，
     * 与 {@link #ATTR_REQUEST_PLATFORM} 解耦，便于同一平台下区分多个端点（如 OpenAI 的 chat/responses）。
     */
    public static final String ATTR_REQUEST_FORMAT = "gateway_request_format";

    /**
     * 根据请求路径派发到对应的网关处理器。
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @param body     请求体（原始字符串）
     */
    public void dispatch(HttpServletRequest request, HttpServletResponse response, String body) throws IOException {
        String path = request.getServletPath();
        GatewayClientRoute route = GatewayClientRoute.resolve(path).orElse(null);
        if (route == null) {
            log.warn("路由失败: 未找到路径映射 | path={}", path);
            response.setStatus(404);
            response.setContentType(GatewayHttpHeaderPolicy.MEDIA_TYPE_JSON_UTF8);
            response.getWriter().write("{\"error\":\"Unsupported API path: " + path + "\"}");
            return;
        }
        Platform platform = route.platform();
        String format = route.format();
        // 存入 request attribute，供 AbstractGatewayHandler 判断是否需要协议翻译
        request.setAttribute(ATTR_REQUEST_PLATFORM, platform);
        if (format != null) {
            request.setAttribute(ATTR_REQUEST_FORMAT, format);
        }
        log.debug("路由解析: path={} -> platform={}, format={}", path, platform.name(), format);

        IGatewayHandler handler = factory.getHandler(platform);
        log.debug("派发到 Handler: {}", handler.getClass().getSimpleName());
        handler.handle(body, request, response);
    }
}
