package com.landgate.domain.balance.model.entity;

import com.landgate.types.enums.BalanceFundingType;
import com.landgate.types.enums.BalanceTransactionStatus;
import com.landgate.types.enums.BalanceTransactionType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 管理员余额流水视图实体 —— 包含余额流水和用户冗余信息，用于后台审计列表。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class AdminBalanceTransactionEntity {

    private Long id;
    private Long userId;
    /** 用户邮箱 */
    private String userEmail;
    private BalanceTransactionType transactionType;
    private BalanceFundingType fundingType;
    private BigDecimal amount;
    private BigDecimal cashIncomeAmount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String operatorType;
    private String operatorId;
    private String remark;
    private BalanceTransactionStatus status;
    private String failureReason;
    private Instant completedAt;
    private Instant createdAt;
}
