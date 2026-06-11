package com.landgate.api.admin.dto;

import com.landgate.types.enums.Status;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Admin API Key 管理 DTOs。
 */
public final class ApiKeyAdminDTOs {

    public record CreateApiKeyAdminRequest(
            @NotBlank String name,
            Long groupId,
            BigDecimal quota,
            String ipWhitelist,
            String ipBlacklist,
            Instant expiresAt,
            String status
    ) {}

    public record UpdateApiKeyAdminRequest(
            String name,
            Long groupId,
            BigDecimal quota,
            String ipWhitelist,
            String ipBlacklist,
            Instant expiresAt,
            String status
    ) {}

    public record ApiKeyAdminResponse(
            Long id,
            Long userId,
            String key,
            String name,
            Long groupId,
            Status status,
            Instant lastUsedAt,
            String ipWhitelist,
            String ipBlacklist,
            BigDecimal quota,
            BigDecimal quotaUsed,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
