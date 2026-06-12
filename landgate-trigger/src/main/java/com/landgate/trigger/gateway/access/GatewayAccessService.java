package com.landgate.trigger.gateway.access;

import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.billing.service.BillingDomainService;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.trigger.gateway.billing.BalanceDomainService;
import com.landgate.trigger.gateway.error.IErrorWriter;
import com.landgate.trigger.gateway.group.GatewayGroupResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayAccessService {

    private final IUserRepository userRepository;
    private final BillingDomainService billingDomainService;
    private final BalanceDomainService balanceDomainService;
    private final GatewayGroupResolver gatewayGroupResolver;

    public GatewayAccessResult check(String requestId,
                                     HttpServletRequest request,
                                     HttpServletResponse response,
                                     IErrorWriter errorWriter) throws IOException {
        Long apiKeyId = (Long) request.getAttribute("api_key_id");
        Long userId = (Long) request.getAttribute("user_id");
        Long groupId = (Long) request.getAttribute("group_id");

        if (apiKeyId == null) {
            log.warn("[{}] 认证失败: 缺少 API Key (api_key_id=null)", requestId);
            errorWriter.writeError(response, 401, "authentication_error", "Missing API key");
            return stop();
        }

        GroupEntity group = gatewayGroupResolver.loadGroup(groupId);
        if (group == null) {
            log.warn("[{}] 权限拒绝: group_id={} 不存在或已删除 | api_key_id={}", requestId, groupId, apiKeyId);
            errorWriter.writeError(response, 403, "permission_error",
                    "API key has no group assigned. Contact admin to assign a group.");
            return stop();
        }
        if (!group.isActive()) {
            log.warn("[{}] 权限拒绝: group '{}' 已禁用 | api_key_id={}", requestId, group.getName(), apiKeyId);
            errorWriter.writeError(response, 403, "permission_error",
                    "Group '" + group.getName() + "' is disabled.");
            return stop();
        }

        try {
            billingDomainService.checkQuota(apiKeyId);
        } catch (com.landgate.types.exception.AuthenticationException e) {
            log.warn("[{}] 配额超限: api_key_id={} | {}", requestId, apiKeyId, e.getMessage());
            errorWriter.writeError(response, 429, "quota_exceeded", e.getMessage());
            return stop();
        }

        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("[{}] 用户不存在: user_id={}", requestId, userId);
            errorWriter.writeError(response, 401, "authentication_error", "User not found");
            return stop();
        }
        if (!user.isPrivileged() && !balanceDomainService.hasBalance(userId)) {
            log.warn("[{}] 余额不足: user_id={}, is_privileged={}", requestId, userId, user.isPrivileged());
            errorWriter.writeError(response, 402, "insufficient_balance",
                    "Insufficient balance. Please recharge your account.");
            return stop();
        }

        return new GatewayAccessResult(false, apiKeyId, userId, groupId, group, user);
    }

    private GatewayAccessResult stop() {
        return new GatewayAccessResult(true, null, null, null, null, null);
    }
}
