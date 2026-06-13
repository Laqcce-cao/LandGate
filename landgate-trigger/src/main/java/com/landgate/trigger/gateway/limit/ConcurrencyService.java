package com.landgate.trigger.gateway.limit;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 并发控制服务 —— 基于 Redisson {@link RPermitExpirableSemaphore} 实现分布式并发控制。
 * <p>
 * 每个上游账号的并发槽位存储在 Redis 中，支持多实例部署。
 * 获取槽位时先做前置检查（无可用槽位直接返回false），
 * 有可用槽位时使用指数退避 + jitter 等待，避免惊群效应。
 * 默认最大等待 30 秒。
 * <p>
 * 每个 permit 带有 5 分钟租约，到期自动归还，解决实例崩溃导致的槽位泄漏问题。
 * 流式请求需通过 {@link #renewLease} 定期续约。
 */
@Slf4j
@Component
public class ConcurrencyService {

    private final RedissonClient redissonClient;

    private static final int MAX_WAIT_SECONDS = 30;
    private static final long BASE_WAIT_MS = 100;
    private static final long MAX_POLL_MS = 2_000;
    private static final double BACKOFF_MULTIPLIER = 1.5;
    private static final double JITTER_FACTOR = 0.2;
    private static final Duration MAX_BUCKET_TTL = Duration.ofHours(24);
    private static final long PERMIT_LEASE_SECONDS = 300;

    private static final String SEMAPHORE_PREFIX = "concurrency:slot:";
    private static final String MAX_BUCKET_PREFIX = "concurrency:slot:max:";

    public ConcurrencyService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 尝试获取指定账号的并发槽位，成功时返回带 5 分钟租约的 {@link ConcurrencySlot}。
     *
     * @param accountId      上游账号 ID
     * @param maxConcurrency 该账号的最大并发数
     * @return 获取成功返回 ConcurrencySlot，失败返回 null
     */
    public ConcurrencySlot tryAcquire(Long accountId, int maxConcurrency) {
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(
                SEMAPHORE_PREFIX + accountId);

        // 确保信号量已按正确的并发数初始化
        ensurePermits(semaphore, accountId, maxConcurrency);

        // 前置检查：无可用槽位时直接返回，不做无效等待
        int available = semaphore.availablePermits();
        if (available <= 0) {
            log.debug("并发槽位已满(快速失败): account_id={}, max={}, available={}",
                    accountId, maxConcurrency, available);
            return null;
        }

        log.debug("开始等待并发槽位: account_id={}, available={}, max={}",
                accountId, available, maxConcurrency);

        long baseWaitMs = BASE_WAIT_MS;
        long deadline = System.currentTimeMillis() + MAX_WAIT_SECONDS * 1000L;
        int waitIteration = 0;

        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                log.warn("并发槽位等待超时: account_id={}, max={}, 等待{}ms",
                        accountId, maxConcurrency, MAX_WAIT_SECONDS * 1000L);
                return null;
            }

            try {
                long pollMs = Math.min(remaining, MAX_POLL_MS);
                String permitId = semaphore.tryAcquire(pollMs, PERMIT_LEASE_SECONDS, TimeUnit.SECONDS);
                if (permitId != null) {
                    log.debug("并发槽位获取成功: account_id={}, available_after={}, wait_iterations={}",
                            accountId, semaphore.availablePermits(), waitIteration);
                    return ConcurrencySlot.of(permitId, accountId);
                }
                waitIteration++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("并发等待被中断: account_id={}", accountId);
                return null;
            }

            // 指数退避 + ±20% jitter
            double jitter = baseWaitMs * JITTER_FACTOR * (ThreadLocalRandom.current().nextDouble() * 2 - 1);
            long sleepMs = (long) (baseWaitMs + jitter);
            log.debug("并发槽位重试: account_id={}, iteration={}, backoff={}ms",
                    accountId, waitIteration, sleepMs);
            baseWaitMs = (long) Math.min(baseWaitMs * BACKOFF_MULTIPLIER, MAX_POLL_MS);

            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /**
     * 释放指定账号的并发槽位。
     * <p>
     * permit 已过期时 release 为 no-op，不会抛异常。
     *
     * @param slot 之前获取的并发槽位
     */
    public void release(ConcurrencySlot slot) {
        if (slot == null || !slot.isAcquired()) return;
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(
                SEMAPHORE_PREFIX + slot.getAccountId());
        semaphore.release(slot.getPermitId());
        log.debug("并发槽位释放: account_id={}, available_after={}",
                slot.getAccountId(), semaphore.availablePermits());
    }

    /**
     * 续约指定槽位的租约，重置为 5 分钟。
     * <p>
     * 流式请求应在 SSE 循环中定期调用（建议每 60 秒），防止长连接期间 permit 过期。
     *
     * @param slot 需要续约的并发槽位
     * @return true 表示续约成功，false 表示 permit 已过期
     */
    public boolean renewLease(ConcurrencySlot slot) {
        if (slot == null || !slot.isAcquired()) return false;
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(
                SEMAPHORE_PREFIX + slot.getAccountId());
        boolean renewed = semaphore.updateLeaseTime(
                slot.getPermitId(), PERMIT_LEASE_SECONDS, TimeUnit.SECONDS);
        if (!renewed) {
            log.warn("Concurrency lease renewal failed (permit may have expired): account_id={}, permit_id={}",
                    slot.getAccountId(), slot.getPermitId());
        }
        return renewed;
    }

    /**
     * 获取指定账号当前活跃的并发数。
     *
     * @param accountId 上游账号 ID
     * @return 当前占用的槽位数
     */
    public int getActiveCount(Long accountId) {
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(
                SEMAPHORE_PREFIX + accountId);
        int available = semaphore.availablePermits();
        if (available < 0) {
            return 0;
        }
        // 从 max bucket 读取总并发数计算活跃数
        RBucket<Integer> maxBucket = redissonClient.getBucket(MAX_BUCKET_PREFIX + accountId);
        Integer max = maxBucket.get();
        if (max == null || max <= 0) {
            return 0;
        }
        return Math.max(0, max - available);
    }

    // ---- 内部辅助方法 ----

    /**
     * 确保 Redis 中信号量的 permit 数量与预期的 maxConcurrency 一致。
     * <p>
     * 首次初始化时使用 trySetPermits（仅在信号量空闲时成功）；
     * 并发数增大时通过 addPermits 增量调整；
     * 并发数减小时只更新存储的 max 值，多余 permit 随 release 自然排干。
     */
    private void ensurePermits(RPermitExpirableSemaphore semaphore, Long accountId, int maxConcurrency) {
        String maxKey = MAX_BUCKET_PREFIX + accountId;
        RBucket<Integer> maxBucket = redissonClient.getBucket(maxKey);

        Integer currentMax = maxBucket.get();

        if (currentMax == null) {
            // 首次初始化：用 setIfAbsent 防止多实例竞争
            boolean initialized = maxBucket.setIfAbsent(maxConcurrency);
            if (initialized) {
                semaphore.trySetPermits(maxConcurrency);
                log.debug("Concurrency semaphore initialized: account_id={}, max={}", accountId, maxConcurrency);
            }
            maxBucket.expire(MAX_BUCKET_TTL);
        } else if (currentMax != maxConcurrency) {
            // 并发数变更：用 CAS 保证只有一个实例执行调整
            if (maxBucket.compareAndSet(currentMax, maxConcurrency)) {
                if (maxConcurrency > currentMax) {
                    semaphore.addPermits(maxConcurrency - currentMax);
                    log.debug("Concurrency max increased: account_id={}, {} -> {}",
                            accountId, currentMax, maxConcurrency);
                } else {
                    log.debug("Concurrency max decreased: account_id={}, {} -> {} (drains naturally)",
                            accountId, currentMax, maxConcurrency);
                }
            }
            maxBucket.expire(MAX_BUCKET_TTL);
        }
    }
}
