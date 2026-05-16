package com.landgate.api.payment.dto;

import java.math.BigDecimal;

public final class PaymentDTOs {
    private PaymentDTOs() {}

    public record CreateBalanceOrderRequest(BigDecimal amount, String paymentType) {}
    public record CreateSubscriptionOrderRequest(
            Long planId, Long groupId, Integer days,
            BigDecimal amount, String paymentType) {}
    public record RefundRequest(String reason) {}
}
