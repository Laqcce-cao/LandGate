package com.landgate.infrastructure.dao.po;

import com.landgate.types.enums.OrderStatus;
import com.landgate.types.enums.OrderType;
import com.landgate.types.enums.PaymentType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 支付订单持久化对象 —— 对应 <code>payment_orders</code> 表。
 * <p>
 * 记录所有支付/充值/订阅订单的完整生命周期信息，包括金额、交易号、退款等。
 * 不使用软删除（支付记录不允许删除）。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PaymentOrderPO extends BasePO {

    /** 主键，自增 */
    private Long id;

    // ==================== 用户信息 ====================

    /** 用户 ID */
    private Long userId;

    /** 用户邮箱（冗余） */
    private String userEmail;

    /** 用户名（冗余） */
    @Builder.Default
    private String userName = "";

    /** 用户备注 */
    @Builder.Default
    private String userNotes = "";

    // ==================== 金额信息 ====================

    /** 订单金额（USD） */
    private BigDecimal amount;

    /** 实际支付金额（USD） */
    private BigDecimal payAmount;

    /** 手续费率 */
    @Builder.Default
    private BigDecimal feeRate = BigDecimal.ZERO;

    // ==================== 交易标识 ====================

    /** 充值码 */
    private String rechargeCode;

    /** 商户订单号 */
    private String outTradeNo;

    /** 支付渠道 */
    private PaymentType paymentType;

    /** 支付平台交易号 */
    private String paymentTradeNo;

    // ==================== 支付链接 ====================

    /** 支付页面 URL */
    private String payUrl;

    /** 二维码内容 */
    private String qrCode;

    /** 二维码图片 */
    private String qrCodeImg;

    // ==================== 订单类型与订阅 ====================

    /** 订单类型 */
    @Builder.Default
    private OrderType orderType = OrderType.BALANCE;

    /** 订阅计划 ID */
    private Long planId;

    /** 订阅分组 ID */
    private Long subscriptionGroupId;

    /** 订阅天数 */
    private Integer subscriptionDays;

    // ==================== 提供商信息 ====================

    /** 提供商实例 ID */
    private String providerInstanceId;

    /** 提供商标识 */
    private String providerKey;

    /** 提供商快照（JSON） */
    private String providerSnapshot;

    // ==================== 订单状态 ====================

    /** 订单状态 */
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    // ==================== 退款信息 ====================

    /** 已退款金额（USD） */
    @Builder.Default
    private BigDecimal refundAmount = BigDecimal.ZERO;

    /** 退款原因 */
    private String refundReason;

    /** 退款时间 */
    private Instant refundAt;

    /** 是否强制退款 */
    @Builder.Default
    private Boolean forceRefund = false;

    /** 退款申请时间 */
    private Instant refundRequestedAt;

    /** 退款申请原因 */
    private String refundRequestReason;

    /** 退款申请人 */
    private String refundRequestedBy;

    // ==================== 时间戳 ====================

    /** 订单过期时间 */
    private Instant expiresAt;

    /** 支付成功时间 */
    private Instant paidAt;

    /** 订单完成时间 */
    private Instant completedAt;

    /** 订单失败时间 */
    private Instant failedAt;

    /** 失败原因 */
    private String failedReason;

    // ==================== 客户端信息 ====================

    /** 客户端 IP */
    @Builder.Default
    private String clientIp = "";

    /** 来源主机 */
    @Builder.Default
    private String srcHost = "";

    /** 来源 URL */
    @Builder.Default
    private String srcUrl = "";

    public boolean isPending() { return OrderStatus.PENDING == status; }
    public boolean isPaid() {
        return OrderStatus.PAID == status || OrderStatus.RECHARGING == status || OrderStatus.COMPLETED == status;
    }
    public boolean isCompleted() { return OrderStatus.COMPLETED == status; }
    public boolean isBalanceOrder() { return OrderType.BALANCE == orderType; }
    public boolean isSubscriptionOrder() { return OrderType.SUBSCRIPTION == orderType; }
}
