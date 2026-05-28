package com.landgate.api.billing.dto;

import java.math.BigDecimal;

public record ModelStats(
        String model,
        Long totalTokens,
        BigDecimal totalCost,
        Long callCount
) {}
