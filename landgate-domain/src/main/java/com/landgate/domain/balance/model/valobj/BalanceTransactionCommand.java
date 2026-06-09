package com.landgate.domain.balance.model.valobj;

import com.landgate.types.enums.BalanceFundingType;
import com.landgate.types.enums.BalanceTransactionType;

import java.math.BigDecimal;

/**
 * 余额流水创建命令 —— 调用统一余额变动领域服务时传入的业务参数。
 */
public record BalanceTransactionCommand(
        Long userId,
        BalanceTransactionType transactionType,
        BalanceFundingType fundingType,
        BigDecimal amount,
        BigDecimal cashIncomeAmount,
        String sourceType,
        String sourceId,
        String operatorType,
        String operatorId,
        String remark,
        String metadata,
        boolean allowNegative
) {}
