package com.landgate.trigger.http.gateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * Antigravity 网关控制器 —— 处理 Antigravity 专用路由的代理转发。
 */
@Slf4j
@RestController
public class AntigravityGatewayController {

    @PostMapping("/antigravity/v1/messages")
    public void messages(@RequestBody String body,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        writeUnsupported(response);
    }

    @PostMapping("/antigravity/v1/chat/completions")
    public void chatCompletions(@RequestBody String body,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        writeUnsupported(response);
    }

    @PostMapping("/antigravity/v1beta/models/{modelPath}/**")
    public void geminiModels(@RequestBody(required = false) String body,
                             @PathVariable String modelPath,
                             HttpServletRequest request,
                             HttpServletResponse response) throws IOException {
        writeUnsupported(response);
    }

    private static void writeUnsupported(HttpServletResponse response) throws IOException {
        response.setStatus(404);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":{\"message\":\"Antigravity gateway is not supported in this build\",\"type\":\"unsupported_error\"}}");
    }
}
