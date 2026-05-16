package com.landgate.api.payment.dto;

import com.landgate.types.enums.OrderStatus;
import com.landgate.types.enums.OrderType;
import com.landgate.types.enums.PaymentType;
import lombok.Builder;
import java.math.BigDecimal;
import java.time.Instant;

@Builder
public record PaymentOrderDetail(
        Long id, Long userId, BigDecimal amount, BigDecimal payAmount,
        OrderType orderType, OrderStatus status, PaymentType paymentType,
        String outTradeNo, String paymentTradeNo, String payUrl,
        BigDecimal refundAmount, Instant paidAt, Instant expiresAt, Instant createdAt
) {}
