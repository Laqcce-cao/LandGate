package com.landgate.api.marketing.dto;

import com.landgate.types.enums.RedeemCodeType;
import com.landgate.types.enums.Status;
import lombok.Builder;
import java.math.BigDecimal;
import java.time.Instant;

@Builder
public record RedeemCodeDetail(
        Long id, String code, RedeemCodeType type,
        BigDecimal amount, Integer maxUses, Integer usedCount,
        Status status, Instant expiresAt, Instant createdAt
) {}
