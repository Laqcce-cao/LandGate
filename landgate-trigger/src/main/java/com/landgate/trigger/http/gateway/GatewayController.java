package com.landgate.trigger.http.gateway;

import com.landgate.trigger.gateway.GatewayService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * Anthropic 网关控制器 —— 处理 Anthropic Messages API 的代理转发。
 * <p>
 * 路由映射：POST /v1/messages → gatewayService.handleMessages()。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayService gatewayService;

    @PostMapping("/v1/messages")
    public void messages(@RequestBody String body,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        log.info("POST /v1/messages: content_length={}, remote_addr={}",
                body != null ? body.length() : 0, request.getRemoteAddr());
        gatewayService.handleMessages(body, request, response);
    }

    @PostMapping("/v1/messages/count_tokens")
    public void countTokens(@RequestBody String body,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        log.info("POST /v1/messages/count_tokens");
        GatewayService.writeAnthropicError(response, 501, "not_implemented",
                "count_tokens is not yet implemented");
    }

    @GetMapping("/v1/models")
    public void models(HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.info("GET /v1/models");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"data\":[],\"has_more\":false,\"first_id\":null,\"last_id\":null}");
    }
}
