package com.landgate.trigger.http.gateway;

import com.landgate.trigger.gateway.GatewayDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.UUID;

/**
 * 网关统一入口 —— 处理所有 AI API 代理转发请求。
 * <p>
 * URL 路径决定协议格式（Anthropic / OpenAI / Gemini），
 * 由 {@link GatewayDispatcher} 根据路径选择对应的 Handler。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayDispatcher dispatcher;

    private static final String ATTR_GATEWAY_MODEL = "gateway_model";
    private static final String ATTR_GATEWAY_UPSTREAM_PATH = "gateway_upstream_path";
    private static final String ATTR_REQUEST_ID = "gateway_request_id";

    // ---- Anthropic Messages API ----

    @PostMapping("/v1/messages")
    public void messages(@RequestBody String body,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTR_REQUEST_ID, requestId);
        log.info("[{}] => POST /v1/messages | content_length={} | remote_addr={} | ua={}",
                requestId, body != null ? body.length() : 0,
                request.getRemoteAddr(), request.getHeader("User-Agent"));
        dispatcher.dispatch(request, response, body);
    }

    @PostMapping("/v1/messages/count_tokens")
    public void countTokens(@RequestBody String body,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        log.info("POST /v1/messages/count_tokens");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"type\":\"error\",\"error\":{\"type\":\"not_implemented\",\"message\":\"count_tokens is not yet implemented\"}}");
    }

    @GetMapping("/v1/models")
    public void models(HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.info("GET /v1/models");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"data\":[],\"has_more\":false,\"first_id\":null,\"last_id\":null}");
    }

    // ---- OpenAI Chat Completions API ----

    @PostMapping("/v1/chat/completions")
    public void chatCompletions(@RequestBody String body,
                                HttpServletRequest request,
                                HttpServletResponse response) throws IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTR_REQUEST_ID, requestId);
        log.info("[{}] => POST /v1/chat/completions | content_length={} | remote_addr={} | ua={}",
                requestId, body != null ? body.length() : 0,
                request.getRemoteAddr(), request.getHeader("User-Agent"));

        Long groupId = (Long) request.getAttribute("group_id");
        if (groupId == null) {
            log.warn("[{}] 缺少 API Key 认证 (group_id=null)，返回 401", requestId);
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":{\"message\":\"Missing API key\",\"type\":\"authentication_error\",\"param\":null,\"code\":null}}");
            return;
        }
        log.info("[{}] 认证通过: group_id={}", requestId, groupId);

        dispatcher.dispatch(request, response, body);
    }

    // ---- OpenAI Responses API ----

    @PostMapping("/v1/responses")
    public void responses(@RequestBody String body,
                          HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTR_REQUEST_ID, requestId);
        log.info("[{}] => POST /v1/responses | content_length={} | remote_addr={} | ua={}",
                requestId, body != null ? body.length() : 0,
                request.getRemoteAddr(), request.getHeader("User-Agent"));

        Long groupId = (Long) request.getAttribute("group_id");
        if (groupId == null) {
            log.warn("[{}] 缺少 API Key 认证 (group_id=null)，返回 401", requestId);
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":{\"message\":\"Missing API key\",\"type\":\"authentication_error\",\"param\":null,\"code\":null}}");
            return;
        }
        log.info("[{}] 认证通过: group_id={}", requestId, groupId);

        dispatcher.dispatch(request, response, body);
    }

    // ---- Gemini API ----

    @PostMapping("/v1beta/models/{modelPath}/**")
    public void proxyGemini(@RequestBody(required = false) String body,
                            @PathVariable String modelPath,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTR_REQUEST_ID, requestId);
        String fullPath = request.getServletPath();
        log.info("[{}] => POST {} | modelPath={} | bodySize={} | ua={}",
                requestId, fullPath, modelPath,
                body != null ? body.length() : 0, request.getHeader("User-Agent"));

        // 将 Gemini 特有的路径信息注入 request attribute
        request.setAttribute(ATTR_GATEWAY_MODEL, modelPath);
        request.setAttribute(ATTR_GATEWAY_UPSTREAM_PATH, fullPath);

        Long groupId = (Long) request.getAttribute("group_id");
        if (groupId == null) {
            log.warn("[{}] 缺少 API Key 认证 (group_id=null)，返回 401", requestId);
            writeGoogleError(response, 401, "MISSING_API_KEY", "Missing API key");
            return;
        }
        log.info("[{}] 认证通过: group_id={}", requestId, groupId);

        dispatcher.dispatch(request, response, body);
    }

    private static void writeGoogleError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format(
                "{\"error\":{\"code\":%d,\"message\":\"%s\",\"status\":\"%s\"}}",
                status, escapeJson(message), code));
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
