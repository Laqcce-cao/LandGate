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
 * OpenAI 网关控制器 —— 处理 OpenAI Chat Completions API 的代理转发。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OpenAiGatewayController {

    private final GatewayHandlerFactory factory;
    private final IGroupRepository groupRepository;

    @PostMapping("/v1/chat/completions")
    public void chatCompletions(@RequestBody String body,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        log.info("POST /v1/chat/completions");

        Long groupId = (Long) request.getAttribute("group_id");
        if (groupId == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":{\"message\":\"Missing API key\",\"type\":\"authentication_error\",\"param\":null,\"code\":null}}");
            return;
        }

        GroupEntity group = groupRepository.findById(groupId)
                .filter(g -> g.getDeletedAt() == null)
                .orElse(null);
        if (group == null) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":{\"message\":\"API key has no group assigned.\",\"type\":\"permission_error\",\"param\":null,\"code\":null}}");
            return;
        }

        IGatewayHandler handler = factory.getHandler(group.getPlatform());
        handler.handle(body, request, response);
    }
}
