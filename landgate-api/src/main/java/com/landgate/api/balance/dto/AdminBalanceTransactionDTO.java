package com.landgate.api.balance.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 管理员余额流水 DTO —— 展示全站用户余额变动和真实收款信息。
 */
public record AdminBalanceTransactionDTO(
        /** 余额流水 ID */
        Long id,
        /** 用户 ID */
        Long userId,
        /** 用户邮箱 */
        String userEmail,
        /** 余额变动业务类型 */
        String transactionType,
        /** 资金性质 */
        String fundingType,
        /** 用户余额变动金额 */
        BigDecimal amount,
        /** 真实现金收入金额，仅管理员可见 */
        BigDecimal cashIncomeAmount,
        /** 余额变动前金额 */
        BigDecimal balanceBefore,
        /** 余额变动后金额 */
        BigDecimal balanceAfter,
        /** 操作人类型 */
        String operatorType,
        /** 操作人 ID */
        String operatorId,
        /** 备注 */
        String remark,
        /** 处理状态 */
        String status,
        /** 失败原因 */
        String failureReason,
        /** 完成时间 */
        Instant completedAt,
        /** 创建时间 */
        Instant createdAt
) {}
