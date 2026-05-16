package com.landgate.api.billing.dto;

import lombok.Builder;
import java.math.BigDecimal;
import java.time.Instant;

@Builder
public record UsageLogDetail(
        Long id, Long userId, Long apiKeyId, Long accountId,
        String requestId, String model, String billingMode,
        Integer inputTokens, Integer outputTokens,
        BigDecimal totalCost, BigDecimal actualCost,
        Boolean stream, Integer durationMs, Instant createdAt
) {}
