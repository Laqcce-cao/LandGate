package com.landgate.trigger.http.gateway;

import com.landgate.trigger.gateway.IGatewayHandler;
import com.landgate.domain.group.adapter.repository.IGroupRepository;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.trigger.gateway.GatewayHandlerFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * Anthropic 网关控制器 —— 处理 Anthropic Messages API 的代理转发。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayHandlerFactory factory;
    private final IGroupRepository groupRepository;

    @PostMapping("/v1/messages")
    public void messages(@RequestBody String body,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        log.info("POST /v1/messages: content_length={}, remote_addr={}",
                body != null ? body.length() : 0, request.getRemoteAddr());
        IGatewayHandler handler = resolveHandler(request);
        if (handler != null) {
            handler.handle(body, request, response);
        }
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

    private IGatewayHandler resolveHandler(HttpServletRequest request) {
        Long groupId = (Long) request.getAttribute("group_id");
        if (groupId == null) return null;
        GroupEntity group = groupRepository.findById(groupId)
                .filter(g -> g.getDeletedAt() == null)
                .orElse(null);
        if (group == null) return null;
        return factory.getHandler(group.getPlatform());
    }
}
