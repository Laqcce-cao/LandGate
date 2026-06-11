package com.landgate.api.marketing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record PromoCodeCreateRequest(
        @NotBlank String code,
        @NotBlank String discountType,
        @NotNull BigDecimal discountValue,
        BigDecimal minOrderAmount,
        BigDecimal maxDiscountAmount,
        Integer maxUses,
        Integer maxUsesPerUser,
        String startsAt,
        String expiresAt,
        String notes
) {}
