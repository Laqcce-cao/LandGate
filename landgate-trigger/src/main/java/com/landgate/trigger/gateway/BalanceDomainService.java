package com.landgate.trigger.gateway;

import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.infrastructure.balance.BalanceRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 余额编排服务 —— 在触发层协调 Redis 与数据库之间的余额操作。
 * <p>
 * 通过 Redis 进行高性能余额扣减，Redis 不可用时回退到直接数据库更新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceDomainService {

    private final BalanceRedisService balanceRedisService;
    private final IUserRepository userRepository;

    /**
     * 在上游 API 调用成功后扣减余额。
     * 即使余额变为负数也会扣减（由前置检查负责拦截零余额用户）。
     */
    public void deduct(Long userId, BigDecimal cost) {
        if (cost.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        int result = balanceRedisService.tryDeduct(userId, cost);
        if (result == -2) {
            // Balance not loaded in Redis — load from DB and retry
            UserEntity user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                throw new IllegalStateException("User not found for deduction: user_id=" + userId);
            }
            balanceRedisService.loadBalanceIfAbsent(userId, user.getBalance());
            result = balanceRedisService.tryDeduct(userId, cost);
        }

        if (result == 1) {
            log.debug("Balance deducted: user_id={}, cost={}", userId, cost);
            return;
        }
        throw new IllegalStateException("Balance deduction failed: user_id=" + userId
                + ", cost=" + cost + ", result=" + result);
    }

    /**
     * 在转发请求至上游前检查用户是否有余额。
     */
    public boolean hasBalance(Long userId) {
        // Try Redis first
        BigDecimal redisBalance = balanceRedisService.getBalance(userId);
        if (redisBalance != null) {
            return redisBalance.compareTo(BigDecimal.ZERO) > 0;
        }
        // Fall back to DB
        return userRepository.findById(userId)
                .map(u -> u.getBalance().compareTo(BigDecimal.ZERO) > 0)
                .orElse(false);
    }

    /**
     * 支付或兑换到账后将最新余额同步至 Redis。
     */
    public void creditBalance(Long userId, BigDecimal newBalance) {
        balanceRedisService.loadBalance(userId, newBalance);
    }
}
