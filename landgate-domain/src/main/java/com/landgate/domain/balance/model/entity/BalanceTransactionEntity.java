package com.landgate.domain.balance.model.entity;

import com.landgate.types.enums.BalanceFundingType;
import com.landgate.types.enums.BalanceTransactionStatus;
import com.landgate.types.enums.BalanceTransactionType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 余额流水实体 —— 记录用户低频余额变动的来源、金额、现金收入和处理状态。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class BalanceTransactionEntity {

    private Long id;
    private Long userId;

    /** 余额变动业务类型 */
    private BalanceTransactionType transactionType;
    /** 资金性质 */
    private BalanceFundingType fundingType;

    /** 用户余额变动金额 */
    private BigDecimal amount;
    /** 真实现金收入金额 */
    @Builder.Default private BigDecimal cashIncomeAmount = BigDecimal.ZERO;
    /** 余额变动前金额 */
    private BigDecimal balanceBefore;
    /** 余额变动后金额 */
    private BigDecimal balanceAfter;

    /** 来源业务类型 */
    private String sourceType;
    /** 来源业务 ID */
    private String sourceId;
    /** 操作人类型 */
    private String operatorType;
    /** 操作人 ID */
    private String operatorId;

    /** 备注 */
    private String remark;
    /** 扩展信息 JSON */
    private String metadata;

    /** 处理状态 */
    @Builder.Default private BalanceTransactionStatus status = BalanceTransactionStatus.PENDING;
    /** 失败原因 */
    private String failureReason;
    /** 完成时间 */
    private Instant completedAt;

    private Instant createdAt;
    private Instant updatedAt;
}
