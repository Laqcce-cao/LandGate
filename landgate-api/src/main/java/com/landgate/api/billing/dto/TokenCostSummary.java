package com.landgate.api.billing.dto;

import java.math.BigDecimal;

public record TokenCostSummary(
        Long totalTokens,
        BigDecimal totalCost
) {}
