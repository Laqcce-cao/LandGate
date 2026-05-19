package com.landgate.trigger.gateway;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 并发控制服务 —— 基于 Redisson {@link RSemaphore} 实现分布式并发控制。
 * <p>
 * 每个上游账号的并发槽位存储在 Redis 中，支持多实例部署。
 * 等待获取槽位时使用指数退避 + jitter 避免惊群效应。
 * 默认最大等待 30 秒。
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

    private static final String SEMAPHORE_PREFIX = "concurrency:";
    private static final String MAX_BUCKET_PREFIX = "concurrency:max:";

    public ConcurrencyService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 尝试获取指定账号的并发槽位。
     *
     * @param accountId      上游账号 ID
     * @param maxConcurrency 该账号的最大并发数
     * @return true 表示获取成功，false 表示超时或中断
     */
    public boolean tryAcquire(Long accountId, int maxConcurrency) {
        RSemaphore semaphore = redissonClient.getSemaphore(SEMAPHORE_PREFIX + accountId);

        // 确保信号量已按正确的并发数初始化
        ensurePermits(semaphore, accountId, maxConcurrency);

        long baseWaitMs = BASE_WAIT_MS;
        long deadline = System.currentTimeMillis() + MAX_WAIT_SECONDS * 1000L;

        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                log.warn("Concurrency slot timeout: account_id={}, max={}", accountId, maxConcurrency);
                return false;
            }

            try {
                long pollMs = Math.min(remaining, MAX_POLL_MS);
                if (semaphore.tryAcquire(pollMs, TimeUnit.MILLISECONDS)) {
                    log.debug("Concurrency slot acquired: account_id={}, available={}",
                            accountId, semaphore.availablePermits());
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Concurrency wait interrupted: account_id={}", accountId);
                return false;
            }

            // 指数退避 + ±20% jitter
            double jitter = baseWaitMs * JITTER_FACTOR * (ThreadLocalRandom.current().nextDouble() * 2 - 1);
            long sleepMs = (long) (baseWaitMs + jitter);
            baseWaitMs = (long) Math.min(baseWaitMs * BACKOFF_MULTIPLIER, MAX_POLL_MS);

            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * 释放指定账号的并发槽位。
     *
     * @param accountId 上游账号 ID
     */
    public void release(Long accountId) {
        RSemaphore semaphore = redissonClient.getSemaphore(SEMAPHORE_PREFIX + accountId);
        semaphore.release();
        log.debug("Concurrency slot released: account_id={}, available={}",
                accountId, semaphore.availablePermits());
    }

    /**
     * 获取指定账号当前活跃的并发数。
     *
     * @param accountId 上游账号 ID
     * @return 当前占用的槽位数
     */
    public int getActiveCount(Long accountId) {
        RSemaphore semaphore = redissonClient.getSemaphore(SEMAPHORE_PREFIX + accountId);
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
    private void ensurePermits(RSemaphore semaphore, Long accountId, int maxConcurrency) {
        String maxKey = MAX_BUCKET_PREFIX + accountId;
        RBucket<Integer> maxBucket = redissonClient.getBucket(maxKey);

        Integer currentMax = maxBucket.get();

        if (currentMax == null) {
            // 首次初始化：用 setIfAbsent 防止多实例竞争
            boolean initialized = maxBucket.setIfAbsent(maxConcurrency);
            if (initialized) {
                semaphore.trySetPermits(maxConcurrency);
                log.info("Concurrency semaphore initialized: account_id={}, max={}", accountId, maxConcurrency);
            }
            maxBucket.expire(MAX_BUCKET_TTL);
        } else if (currentMax != maxConcurrency) {
            // 并发数变更：用 CAS 保证只有一个实例执行调整
            if (maxBucket.compareAndSet(currentMax, maxConcurrency)) {
                if (maxConcurrency > currentMax) {
                    semaphore.addPermits(maxConcurrency - currentMax);
                    log.info("Concurrency max increased: account_id={}, {} -> {}",
                            accountId, currentMax, maxConcurrency);
                } else {
                    log.info("Concurrency max decreased: account_id={}, {} -> {} (drains naturally)",
                            accountId, currentMax, maxConcurrency);
                }
            }
            maxBucket.expire(MAX_BUCKET_TTL);
        }
    }
}
