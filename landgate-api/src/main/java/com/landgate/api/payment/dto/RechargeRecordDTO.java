package com.landgate.api.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 充值记录 DTO —— 用户侧充值记录列表的轻量响应对象。
 * <p>
 * 数据来源于 payment_orders 表中的余额订单，仅暴露页面展示需要的字段，
 * 避免将支付订单内部字段（如用户备注、客户端 IP、服务商快照等）直接返回给用户。
 */
public record RechargeRecordDTO(
        /** 订单 ID */
        Long id,
        /** 充值金额 */
        BigDecimal amount,
        /** 实际支付金额 */
        BigDecimal payAmount,
        /** 支付方式 */
        String paymentType,
        /** 订单状态 */
        String status,
        /** 商户订单号 */
        String outTradeNo,
        /** 支付平台交易号 */
        String paymentTradeNo,
        /** 支付服务商标识 */
        String providerKey,
        /** 支付时间 */
        Instant paidAt,
        /** 完成时间 */
        Instant completedAt,
        /** 创建时间 */
        Instant createdAt
) {}
