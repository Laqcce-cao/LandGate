package com.landgate.api.payment.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreateOrderRequest(
        @NotNull BigDecimal amount,
        String paymentType,
        String orderType,
        Long planId,
        Long subscriptionGroupId,
        Integer subscriptionDays,
        String returnUrl
) {}
