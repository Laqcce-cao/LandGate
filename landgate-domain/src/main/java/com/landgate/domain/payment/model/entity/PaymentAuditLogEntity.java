package com.landgate.domain.payment.model.entity;

import lombok.*;

import java.time.Instant;

/**
 * 支付审计日志实体 —— 对应数据库 payment_audit_logs 表。
 * <p>
 * 记录支付相关的操作审计日志（创建、确认、退款等）。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class PaymentAuditLogEntity {

    private Long id;

    /** 关联的订单 ID */
    private String orderId;

    /** 操作类型 */
    private String action;

    /** 操作详情 */
    private String detail;

    /** 操作人 */
    private String operator;

    private Instant createdAt;
}
