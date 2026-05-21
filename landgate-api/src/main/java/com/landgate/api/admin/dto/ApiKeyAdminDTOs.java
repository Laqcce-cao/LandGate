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
            BigDecimal rateLimit5h,
            BigDecimal rateLimit1d,
            BigDecimal rateLimit7d,
            String ipWhitelist,
            String ipBlacklist,
            Instant expiresAt,
            String status
    ) {}

    public record UpdateApiKeyAdminRequest(
            String name,
            Long groupId,
            BigDecimal quota,
            BigDecimal rateLimit5h,
            BigDecimal rateLimit1d,
            BigDecimal rateLimit7d,
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
            BigDecimal rateLimit5h,
            BigDecimal usage5h,
            Instant window5hStart,
            BigDecimal rateLimit1d,
            BigDecimal usage1d,
            Instant window1dStart,
            BigDecimal rateLimit7d,
            BigDecimal usage7d,
            Instant window7dStart,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
