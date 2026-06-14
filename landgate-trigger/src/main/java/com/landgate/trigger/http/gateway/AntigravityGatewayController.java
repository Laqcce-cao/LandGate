package com.landgate.trigger.http.gateway;

import com.landgate.types.gateway.GatewayHttpHeaderPolicy;
import com.landgate.types.gateway.GatewayUnsupportedFeaturePolicy;
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
        response.setStatus(GatewayUnsupportedFeaturePolicy.STATUS_NOT_FOUND);
        response.setContentType(GatewayHttpHeaderPolicy.MEDIA_TYPE_JSON_UTF8);
        response.getWriter().write(GatewayUnsupportedFeaturePolicy.openAiUnsupportedBody(
                GatewayUnsupportedFeaturePolicy.ANTIGRAVITY_UNSUPPORTED_MESSAGE));
    }
}
