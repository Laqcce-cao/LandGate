package com.landgate.api.marketing.dto;

import com.landgate.types.enums.DiscountType;
import com.landgate.types.enums.Status;
import lombok.Builder;
import java.math.BigDecimal;
import java.time.Instant;

@Builder
public record PromoCodeDetail(
        Long id, String code, DiscountType discountType,
        BigDecimal discountValue, BigDecimal minOrderAmount,
        BigDecimal maxDiscountAmount, Integer maxUses, Integer usedCount,
        Status status, Instant startsAt, Instant expiresAt, Instant createdAt
) {}
