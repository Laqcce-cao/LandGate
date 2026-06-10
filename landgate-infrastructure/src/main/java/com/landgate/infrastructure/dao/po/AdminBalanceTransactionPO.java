package com.landgate.infrastructure.dao.po;

import com.landgate.types.enums.BalanceFundingType;
import com.landgate.types.enums.BalanceTransactionStatus;
import com.landgate.types.enums.BalanceTransactionType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 管理员余额流水视图 PO —— 查询 balance_transactions 时附带用户邮箱和用户名。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AdminBalanceTransactionPO {
    private Long id;
    private Long userId;
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
