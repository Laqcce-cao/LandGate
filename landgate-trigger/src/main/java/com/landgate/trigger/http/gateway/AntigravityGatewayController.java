package com.landgate.trigger.http.gateway;

import com.landgate.trigger.gateway.GatewayService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * Antigravity 网关控制器 —— 处理 Antigravity 专用路由的代理转发。
 * <p>
 * 路由映射：POST /antigravity/v1/messages 等。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AntigravityGatewayController {

    private final GatewayService gatewayService;
    private final GeminiGatewayController geminiGatewayController;

    @PostMapping("/antigravity/v1/messages")
    public void messages(@RequestBody String body,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        log.info("POST /antigravity/v1/messages: content_length={}", body != null ? body.length() : 0);
        gatewayService.handleMessages(body, request, response);
    }

    @PostMapping("/antigravity/v1/chat/completions")
    public void chatCompletions(@RequestBody String body,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        log.info("POST /antigravity/v1/chat/completions");
        gatewayService.handleMessages(body, request, response);
    }

    @PostMapping("/antigravity/v1beta/models/{modelPath}/**")
    public void geminiModels(@RequestBody(required = false) String body,
                             @PathVariable String modelPath,
                             HttpServletRequest request,
                             HttpServletResponse response) throws IOException {
        log.info("POST /antigravity/v1beta/models/{}", modelPath);
        geminiGatewayController.proxyGemini(body, modelPath, request, response);
    }
}
