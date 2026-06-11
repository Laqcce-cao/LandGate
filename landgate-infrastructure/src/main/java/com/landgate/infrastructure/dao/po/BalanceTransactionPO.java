package com.landgate.infrastructure.dao.po;

import com.landgate.types.enums.BalanceFundingType;
import com.landgate.types.enums.BalanceTransactionStatus;
import com.landgate.types.enums.BalanceTransactionType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 余额流水持久化对象 —— 对应 balance_transactions 表。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class BalanceTransactionPO extends BasePO {

    private Long id;
    private Long userId;
    private BalanceTransactionType transactionType;
    private BalanceFundingType fundingType;
    private BigDecimal amount;
    private BigDecimal cashIncomeAmount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String sourceType;
    private String sourceId;
    private String operatorType;
    private String operatorId;
    private String remark;
    private String metadata;
    private BalanceTransactionStatus status;
    private String failureReason;
    private Instant completedAt;
}
