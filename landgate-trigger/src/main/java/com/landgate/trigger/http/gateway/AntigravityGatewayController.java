package com.landgate.trigger.http.gateway;

import com.landgate.trigger.gateway.GatewayDispatcher;
import com.landgate.trigger.gateway.IGatewayHandler;
import com.landgate.trigger.gateway.GatewayHandlerFactory;
import com.landgate.types.enums.Platform;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * Antigravity 网关控制器 —— 处理 Antigravity 专用路由的代理转发。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AntigravityGatewayController {

    private final GatewayHandlerFactory factory;

    private static final String ATTR_GATEWAY_MODEL = "gateway_model";
    private static final String ATTR_GATEWAY_UPSTREAM_PATH = "gateway_upstream_path";

    @PostMapping("/antigravity/v1/messages")
    public void messages(@RequestBody String body,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        log.info("POST /antigravity/v1/messages: content_length={}", body != null ? body.length() : 0);
        // 显式标记请求平台为 ANTIGRAVITY，路由到独立的 AntigravityGatewayHandler，
        // 避免与 Anthropic 平台共用 Handler 导致 OAuth 伪装等专属逻辑误触发。
        request.setAttribute(GatewayDispatcher.ATTR_REQUEST_PLATFORM, Platform.ANTIGRAVITY);
        request.setAttribute(GatewayDispatcher.ATTR_REQUEST_FORMAT, "messages");
        IGatewayHandler handler = factory.getHandler(Platform.ANTIGRAVITY);
        handler.handle(body, request, response);
    }

    @PostMapping("/antigravity/v1/chat/completions")
    public void chatCompletions(@RequestBody String body,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        log.info("POST /antigravity/v1/chat/completions");
        // Antigravity 平台的 Chat Completions 端点：客户端格式为 chat_completions，
        // platform 仍为 ANTIGRAVITY（账号归属由 platform 决定，请求格式由 format 决定）。
        request.setAttribute(GatewayDispatcher.ATTR_REQUEST_PLATFORM, Platform.ANTIGRAVITY);
        request.setAttribute(GatewayDispatcher.ATTR_REQUEST_FORMAT, "chat_completions");
        IGatewayHandler handler = factory.getHandler(Platform.ANTIGRAVITY);
        handler.handle(body, request, response);
    }

    @PostMapping("/antigravity/v1beta/models/{modelPath}/**")
    public void geminiModels(@RequestBody(required = false) String body,
                             @PathVariable String modelPath,
                             HttpServletRequest request,
                             HttpServletResponse response) throws IOException {
        log.info("POST /antigravity/v1beta/models/{}", modelPath);

        request.setAttribute(ATTR_GATEWAY_MODEL, modelPath);
        request.setAttribute(ATTR_GATEWAY_UPSTREAM_PATH,
                request.getServletPath().replace("/antigravity", ""));

        // Antigravity Gemini 路由：请求格式为 Gemini，仍直接走 Gemini Handler（独立路径，
        // 不经过 Antigravity Handler；Gemini 不存在 OAuth 伪装问题）。
        request.setAttribute(GatewayDispatcher.ATTR_REQUEST_PLATFORM, Platform.GEMINI);
        request.setAttribute(GatewayDispatcher.ATTR_REQUEST_FORMAT, "gemini");
        IGatewayHandler handler = factory.getHandler(Platform.GEMINI);
        handler.handle(body, request, response);
    }
}
