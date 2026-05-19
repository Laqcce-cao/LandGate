package com.landgate.trigger.http.gateway;

import com.landgate.trigger.gateway.IGatewayHandler;
import com.landgate.domain.group.adapter.repository.IGroupRepository;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.trigger.gateway.GatewayHandlerFactory;
import com.landgate.types.enums.Platform;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * Gemini 网关控制器 —— 处理 Google Gemini API 的代理转发。
 * <p>
 * 模型名称来自 URL 路径变量，通过 request attribute 传递给下游处理器。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class GeminiGatewayController {

    private final GatewayHandlerFactory factory;
    private final IGroupRepository groupRepository;

    private static final String ATTR_GATEWAY_MODEL = "gateway_model";
    private static final String ATTR_GATEWAY_UPSTREAM_PATH = "gateway_upstream_path";

    @PostMapping("/v1beta/models/{modelPath}/**")
    public void proxyGemini(@RequestBody(required = false) String body,
                            @PathVariable String modelPath,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        String fullPath = request.getServletPath();
        log.info("POST {}: modelPath={}, bodySize={}", fullPath, modelPath,
                body != null ? body.length() : 0);

        // 将 Gemini 特有的路径信息注入 request attribute
        request.setAttribute(ATTR_GATEWAY_MODEL, modelPath);
        request.setAttribute(ATTR_GATEWAY_UPSTREAM_PATH, fullPath);

        Long groupId = (Long) request.getAttribute("group_id");
        if (groupId == null) {
            writeGoogleError(response, 401, "MISSING_API_KEY", "Missing API key");
            return;
        }

        GroupEntity group = groupRepository.findById(groupId)
                .filter(g -> g.getDeletedAt() == null)
                .orElse(null);
        if (group == null) {
            writeGoogleError(response, 403, "PERMISSION_DENIED", "No group assigned");
            return;
        }

        IGatewayHandler handler = factory.getHandler(group.getPlatform());
        handler.handle(body, request, response);
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
