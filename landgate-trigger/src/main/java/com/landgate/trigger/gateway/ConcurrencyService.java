package com.landgate.trigger.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 并发控制服务 —— 基于 {@link java.util.concurrent.Semaphore} 限制每个上游账号的并发请求数。
 * <p>
 * 使用 ConcurrentHashMap 管理每个账号的信号量，支持动态并发数调整。
 * 默认最大等待 30 秒。
 */
@Slf4j
@Component
public class ConcurrencyService {

    private final Map<Long, AccountSlot> slots = new ConcurrentHashMap<>();
    private static final int MAX_WAIT_SECONDS = 30;

    public boolean tryAcquire(Long accountId, int maxConcurrency) {
        AccountSlot slot = slots.computeIfAbsent(accountId, id -> new AccountSlot(maxConcurrency));
        if (slot.maxConcurrency != maxConcurrency) {
            slot = new AccountSlot(maxConcurrency);
            slots.put(accountId, slot);
        }
        try {
            boolean acquired = slot.semaphore.tryAcquire(MAX_WAIT_SECONDS, TimeUnit.SECONDS);
            if (acquired) {
                log.debug("Concurrency slot acquired: account_id={}, available={}",
                        accountId, slot.semaphore.availablePermits());
            } else {
                log.warn("Concurrency slot timeout: account_id={}, max={}", accountId, maxConcurrency);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Concurrency wait interrupted: account_id={}", accountId);
            return false;
        }
    }

    public void release(Long accountId) {
        AccountSlot slot = slots.get(accountId);
        if (slot != null) {
            slot.semaphore.release();
            log.debug("Concurrency slot released: account_id={}, available={}",
                    accountId, slot.semaphore.availablePermits());
        }
    }

    public int getActiveCount(Long accountId) {
        AccountSlot slot = slots.get(accountId);
        if (slot == null) return 0;
        return slot.maxConcurrency - slot.semaphore.availablePermits();
    }

    private static class AccountSlot {
        final int maxConcurrency;
        final Semaphore semaphore;

        AccountSlot(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            this.semaphore = new Semaphore(maxConcurrency, true);
        }
    }
}
