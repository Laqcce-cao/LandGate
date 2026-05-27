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
        request.setAttribute(GatewayDispatcher.ATTR_REQUEST_PLATFORM, Platform.ANTHROPIC);
        IGatewayHandler handler = factory.getHandler(Platform.ANTHROPIC);
        handler.handle(body, request, response);
    }

    @PostMapping("/antigravity/v1/chat/completions")
    public void chatCompletions(@RequestBody String body,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        log.info("POST /antigravity/v1/chat/completions");
        request.setAttribute(GatewayDispatcher.ATTR_REQUEST_PLATFORM, Platform.OPENAI);
        IGatewayHandler handler = factory.getHandler(Platform.OPENAI);
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

        // Antigravity Gemini 路由：请求格式为 Gemini
        request.setAttribute(GatewayDispatcher.ATTR_REQUEST_PLATFORM, Platform.GEMINI);
        IGatewayHandler handler = factory.getHandler(Platform.GEMINI);
        handler.handle(body, request, response);
    }
}
