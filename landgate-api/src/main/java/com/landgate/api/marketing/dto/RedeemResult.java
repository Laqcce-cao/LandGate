package com.landgate.api.marketing.dto;

import lombok.Builder;
import java.math.BigDecimal;

@Builder
public record RedeemResult(
        boolean success,
        String message,
        String type,
        BigDecimal amount,
        Integer subscriptionDays
) {}
