package com.landgate.domain.balance.adapter.runtime;

import com.landgate.domain.balance.model.valobj.BalanceAdjustResult;

import java.math.BigDecimal;

/**
 * 余额运行态端口 —— 隔离领域层与 Redis 余额实现。
 */
public interface IBalanceRuntime {

    /** 原子调整运行态余额，返回调整前后余额。 */
    BalanceAdjustResult adjustBalance(Long userId, BigDecimal amount, boolean allowNegative);

    /** 仅当运行态余额未加载时，使用数据库余额初始化，避免覆盖并发更新。 */
    boolean loadBalanceIfAbsent(Long userId, BigDecimal balance);

    /** 获取运行态当前余额，返回 null 表示未加载或读取失败。 */
    BigDecimal getBalance(Long userId);
}
