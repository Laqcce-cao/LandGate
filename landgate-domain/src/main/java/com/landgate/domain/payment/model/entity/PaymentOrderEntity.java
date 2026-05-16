package com.landgate.domain.payment.model.entity;

import com.landgate.types.enums.OrderStatus;
import com.landgate.types.enums.OrderType;
import com.landgate.types.enums.PaymentType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 支付订单实体 —— 对应数据库 payment_orders 表。
 * <p>
 * 记录用户的支付订单，支持余额充值和订阅购买两种类型。
 * 订单 30 分钟未支付自动过期，支付确认后自动完成余额履约（充值到账）。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class PaymentOrderEntity {

    private Long id;

    /** 用户 ID */
    private Long userId;

    /** 用户邮箱 */
    private String userEmail;

    /** 用户名 */
    @Builder.Default private String userName = "";

    /** 管理员备注 */
    private String userNotes;

    /** 订单金额 */
    private BigDecimal amount;

    /** 实际支付金额 */
    private BigDecimal payAmount;

    /** 手续费率 */
    @Builder.Default private BigDecimal feeRate = BigDecimal.ZERO;

    /** 充值兑换码（余额订单使用） */
    private String rechargeCode;

    /** 商户订单号 */
    private String outTradeNo;

    /** 支付方式 */
    private PaymentType paymentType;

    /** 支付平台交易号 */
    private String paymentTradeNo;

    /** 支付链接 */
    private String payUrl;

    /** 二维码内容 */
    private String qrCode;

    /** 二维码图片 URL */
    private String qrCodeImg;

    /** 订单类型（余额充值 / 订阅） */
    @Builder.Default
    private OrderType orderType = OrderType.BALANCE;

    private Long planId;
    private Long subscriptionGroupId;
    private Integer subscriptionDays;

    /** 支付服务商实例 ID */
    private String providerInstanceId;

    /** 支付渠道标识 */
    private String providerKey;

    /** 支付服务商快照 */
    private String providerSnapshot;

    /** 订单状态 */
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    /** 退款金额 */
    @Builder.Default private BigDecimal refundAmount = BigDecimal.ZERO;

    /** 退款原因 */
    private String refundReason;

    /** 退款时间 */
    private Instant refundAt;

    /** 是否强制退款 */
    @Builder.Default private Boolean forceRefund = false;

    private Instant refundRequestedAt;
    private String refundRequestReason;
    private String refundRequestedBy;

    /** 订单过期时间 */
    private Instant expiresAt;

    /** 支付时间 */
    private Instant paidAt;

    /** 完成时间 */
    private Instant completedAt;

    /** 失败时间 */
    private Instant failedAt;

    /** 失败原因 */
    private String failedReason;

    /** 客户端 IP */
    @Builder.Default private String clientIp = "";

    /** 来源域名 */
    @Builder.Default private String srcHost = "";

    private String srcUrl;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
}
