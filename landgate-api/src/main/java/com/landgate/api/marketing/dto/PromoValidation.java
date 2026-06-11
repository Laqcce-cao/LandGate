package com.landgate.api.marketing.dto;

import lombok.Builder;
import java.math.BigDecimal;

@Builder
public record PromoValidation(
        boolean valid,
        String code,
        String discountType,
        BigDecimal originalAmount,
        BigDecimal discountedAmount,
        BigDecimal discountValue,
        String message
) {}
