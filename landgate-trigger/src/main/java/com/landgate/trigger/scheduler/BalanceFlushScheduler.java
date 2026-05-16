package com.landgate.trigger.scheduler;

import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.infrastructure.balance.BalanceRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 余额刷新调度器 —— 定期将 Redis 中的余额变更批量同步至 MySQL。
 * <p>
 * 每 30 秒扫描所有脏用户 ID，从 Redis 读取当前余额并更新到数据库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BalanceFlushScheduler {

    private final BalanceRedisService balanceRedisService;
    private final IUserRepository userRepository;

    @Scheduled(fixedDelay = 30_000)
    public void flushBalances() {
        Set<Long> dirtyUserIds;
        try {
            dirtyUserIds = balanceRedisService.getDirtyUserIds();
        } catch (Exception e) {
            log.warn("Failed to query dirty user IDs, skipping flush cycle", e);
            return;
        }

        if (dirtyUserIds.isEmpty()) {
            return;
        }

        int flushed = 0;
        int failed = 0;

        for (Long userId : dirtyUserIds) {
            try {
                BigDecimal currentBalance = balanceRedisService.getBalance(userId);
                if (currentBalance == null) {
                    continue;
                }
                userRepository.updateBalance(userId, currentBalance);
                balanceRedisService.clearDirty(userId);
                flushed++;
            } catch (Exception e) {
                failed++;
                log.error("Failed to flush balance for user_id={}", userId, e);
            }
        }

        if (flushed > 0 || failed > 0) {
            log.info("Balance flush: {} users synced, {} failed", flushed, failed);
        }
    }
}
