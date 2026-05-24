package com.landgate.trigger.http.admin;

import com.landgate.api.admin.dto.ApiKeyAdminDTOs.*;
import com.landgate.domain.auth.model.entity.ApiKeyEntity;
import com.landgate.domain.auth.service.AuthDomainService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理员 API Key 管理控制器 —— 管理员对自己名下的 API Key 进行 CRUD。
 * <p>
 * 路由前缀：{@code /api/v1/admin/api-keys}，需要管理员 JWT 认证。
 * userId 从 JWT 中提取，创建的 Key 归属于管理员本人。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/api-keys")
@RequiredArgsConstructor
public class ApiKeyAdminController {

    private final AuthDomainService authDomainService;

    /**
     * 列出当前管理员的所有 API Key。
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestAttribute("user_id") Long userId) {
        log.debug("Admin list API keys: user_id={}", userId);
        List<ApiKeyEntity> keys = authDomainService.listApiKeys(userId);
        List<ApiKeyAdminResponse> response = keys.stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * 创建 API Key —— 支持设置配额、速率限制、IP 黑白名单等完整字段。
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestAttribute("user_id") Long userId,
                                    @Valid @RequestBody CreateApiKeyAdminRequest req) {
        log.info("Admin create API key: user_id={}, name={}", userId, req.name());
        ApiKeyEntity key = authDomainService.createApiKeyAdmin(userId, req);
        return ResponseEntity.ok(toResponse(key));
    }

    /**
     * 更新 API Key —— 仅更新请求中非 null 的字段。
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@RequestAttribute("user_id") Long userId,
                                    @PathVariable Long id,
                                    @RequestBody UpdateApiKeyAdminRequest req) {
        log.info("Admin update API key: user_id={}, key_id={}", userId, id);
        ApiKeyEntity key = authDomainService.updateApiKey(userId, id, req);
        return ResponseEntity.ok(toResponse(key));
    }

    /**
     * 删除 API Key。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@RequestAttribute("user_id") Long userId,
                                    @PathVariable Long id) {
        log.info("Admin delete API key: user_id={}, key_id={}", userId, id);
        authDomainService.deleteApiKey(userId, id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    private ApiKeyAdminResponse toResponse(ApiKeyEntity k) {
        return new ApiKeyAdminResponse(
                k.getId(), k.getUserId(), k.getKey(), k.getName(), k.getGroupId(),
                k.getStatus(), k.getLastUsedAt(),
                k.getIpWhitelist(), k.getIpBlacklist(),
                k.getQuota(), k.getQuotaUsed(), k.getExpiresAt(),
                k.getCreatedAt(), k.getUpdatedAt()
        );
    }
}
