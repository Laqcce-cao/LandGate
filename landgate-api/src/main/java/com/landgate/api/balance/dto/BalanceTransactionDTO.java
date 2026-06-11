package com.landgate.api.balance.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 用户侧余额明细 DTO —— 仅暴露用户理解余额变动所需的字段。
 */
public record BalanceTransactionDTO(
        /** 余额流水 ID */
        Long id,
        /** 余额变动业务类型 */
        String transactionType,
        /** 资金性质 */
        String fundingType,
        /** 用户余额变动金额 */
        BigDecimal amount,
        /** 变动后余额 */
        BigDecimal balanceAfter,
        /** 备注说明 */
        String remark,
        /** 处理状态 */
        String status,
        /** 余额调整完成时间 */
        Instant completedAt,
        /** 创建时间 */
        Instant createdAt
) {}
