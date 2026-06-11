package com.landgate.infrastructure.adapter.runtime;

import com.landgate.domain.balance.adapter.runtime.IBalanceRuntime;
import com.landgate.domain.balance.model.valobj.BalanceAdjustResult;
import com.landgate.infrastructure.balance.BalanceRedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Redis 余额运行态适配器 —— 将领域层余额运行态端口适配到 Redis 实现。
 */
@Component
@RequiredArgsConstructor
public class RedisBalanceRuntimeAdapter implements IBalanceRuntime {

    private final BalanceRedisService balanceRedisService;

    @Override
    public BalanceAdjustResult adjustBalance(Long userId, BigDecimal amount, boolean allowNegative) {
        return balanceRedisService.adjustBalance(userId, amount, allowNegative);
    }

    @Override
    public boolean loadBalanceIfAbsent(Long userId, BigDecimal balance) {
        return balanceRedisService.loadBalanceIfAbsent(userId, balance);
    }

    @Override
    public BigDecimal getBalance(Long userId) {
        return balanceRedisService.getBalance(userId);
    }
}
