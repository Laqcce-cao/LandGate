package com.landgate.api.account.dto;

import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.enums.Status;
import lombok.Builder;
import java.math.BigDecimal;
import java.time.Instant;

@Builder
public record AccountDetail(
        Long id, String name, Platform platform, AccountType type,
        Status status, Integer concurrency, Integer priority,
        BigDecimal rateMultiplier, Long proxyId,
        Instant lastUsedAt, Instant expiresAt, Instant createdAt
) {}
