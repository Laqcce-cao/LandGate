package com.landgate.domain.balance.model.valobj;

import java.math.BigDecimal;

/**
 * 余额运行态调整结果 —— Redis 原子加减余额后的返回值。
 */
public record BalanceAdjustResult(
        boolean success,
        Long userId,
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        String errorCode,
        String errorMessage
) {
    public static BalanceAdjustResult success(Long userId, BigDecimal amount,
                                              BigDecimal balanceBefore, BigDecimal balanceAfter) {
        return new BalanceAdjustResult(true, userId, amount, balanceBefore, balanceAfter, "OK", null);
    }

    public static BalanceAdjustResult failure(Long userId, BigDecimal amount, String errorCode, String errorMessage) {
        return new BalanceAdjustResult(false, userId, amount, null, null, errorCode, errorMessage);
    }
}
