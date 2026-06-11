package com.landgate.api.marketing.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record RedeemCodeCreateRequest(
        @NotBlank String code,
        @NotBlank String type,
        BigDecimal amount,
        Long groupId,
        Integer subscriptionDays,
        Integer maxUses,
        Long boundUserId,
        String expiresAt,
        String notes
) {}
