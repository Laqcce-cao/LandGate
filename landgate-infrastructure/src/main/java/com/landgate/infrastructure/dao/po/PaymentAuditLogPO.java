package com.landgate.infrastructure.dao.po;

import lombok.*;

import java.time.Instant;

/**
 * 支付审计日志持久化对象 —— 对应 <code>payment_audit_logs</code> 表。
 * <p>
 * 记录支付流程中每个关键操作节点，用于审计追溯。
 * 不继承 BasePO（独立管理 createdAt，无软删除）。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PaymentAuditLogPO {

    /** 主键，自增 */
    private Long id;

    /** 关联订单 ID */
    private String orderId;

    /** 操作类型（如 ORDER_CREATED, REFUND_SUCCESS） */
    private String action;

    /** 操作详情（JSON） */
    @Builder.Default
    private String detail = "{}";

    /** 操作人/系统 */
    private String operator;

    /** 创建时间 */
    @Builder.Default
    private Instant createdAt = Instant.now();
}
