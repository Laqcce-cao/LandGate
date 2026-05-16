package com.landgate.infrastructure.balance;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * 基于 Redis 的余额操作服务 —— 支持高吞吐的原子余额扣减。
 * <p>
 * 使用 Lua 脚本原子性地检查并扣减余额，避免每次请求都 UPDATE 数据库。
 * 变更的用户余额由 {@code BalanceFlushScheduler} 定期批量刷新至 MySQL。
 */
@Slf4j
@Component
public class BalanceRedisService {

    private final RedissonClient redissonClient;

    private static final String BALANCE_PREFIX = "bg:balance:";
    private static final String DIRTY_PREFIX = "bg:dirty:";
    private static final BigDecimal SCALE = new BigDecimal("100000000");

    /**
     * Lua 脚本：原子扣减（始终成功，允许负数）。
     * KEYS[1] = 余额 key, KEYS[2] = 脏标记 key, ARGV[1] = 扣减金额（缩放后的 long 值）。
     *
     * 返回值：1 = 成功, -2 = 余额未加载到 Redis
     */
    private static final String LUA_DEDUCT = """
            local balance = redis.call('GET', KEYS[1])
            if not balance then
                return -2
            end
            redis.call('DECRBY', KEYS[1], tonumber(ARGV[1]))
            redis.call('SET', KEYS[2], '1')
            return 1""";

    public BalanceRedisService(@Qualifier("redissonClient") RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 原子性地从 Redis 中扣减余额（允许负数）。
     * 返回 1 表示成功，-2 表示余额未加载到 Redis。
     */
    public int tryDeduct(Long userId, BigDecimal cost) {
        String balanceKey = BALANCE_PREFIX + userId;
        String dirtyKey = DIRTY_PREFIX + userId;
        long costScaled = cost.multiply(SCALE).setScale(0, RoundingMode.HALF_UP).longValue();

        try {
            RScript script = redissonClient.getScript(StringCodec.INSTANCE);
            Long result = script.eval(RScript.Mode.READ_WRITE,
                    LUA_DEDUCT, RScript.ReturnType.INTEGER,
                    List.of(balanceKey, dirtyKey), costScaled);
            return result != null ? result.intValue() : -2;
        } catch (Exception e) {
            log.error("Redis balance deduction failed: user_id={}, cost={}", userId, cost, e);
            return -2;
        }
    }

    /**
     * 将余额从数据库加载到 Redis（首次请求或充值后调用）。
     */
    public void loadBalance(Long userId, BigDecimal balance) {
        String balanceKey = BALANCE_PREFIX + userId;
        long scaled = balance.multiply(SCALE).setScale(0, RoundingMode.HALF_UP).longValue();
        try {
            redissonClient.getBucket(balanceKey, StringCodec.INSTANCE).set(String.valueOf(scaled));
            log.debug("Balance loaded to Redis: user_id={}, balance={}", userId, balance);
        } catch (Exception e) {
            log.error("Failed to load balance to Redis: user_id={}", userId, e);
        }
    }

    /**
     * 从 Redis 获取当前余额（用于展示或预检查），返回 null 表示未加载。
     */
    public BigDecimal getBalance(Long userId) {
        String balanceKey = BALANCE_PREFIX + userId;
        try {
            var bucket = redissonClient.<String>getBucket(balanceKey, StringCodec.INSTANCE);
            String val = bucket.get();
            if (val != null) {
                return new BigDecimal(val).divide(SCALE, 8, RoundingMode.HALF_UP);
            }
        } catch (Exception e) {
            log.warn("Failed to read balance from Redis: user_id={}", userId, e);
        }
        return null;
    }

    /**
     * 获取所有需要刷新到数据库的脏用户 ID 集合。
     */
    public Set<Long> getDirtyUserIds() {
        try {
            var keys = redissonClient.getKeys();
            Iterable<String> dirtyKeys = keys.getKeysByPattern(DIRTY_PREFIX + "*");
            Set<Long> ids = new java.util.HashSet<>();
            for (String key : dirtyKeys) {
                try {
                    ids.add(Long.parseLong(key.substring(DIRTY_PREFIX.length())));
                } catch (NumberFormatException ignored) {}
            }
            return ids;
        } catch (Exception e) {
            log.warn("Failed to get dirty user IDs from Redis", e);
            return Set.of();
        }
    }

    /**
     * 数据库刷新成功后清除脏标记。
     */
    public void clearDirty(Long userId) {
        try {
            redissonClient.getBucket(DIRTY_PREFIX + userId, StringCodec.INSTANCE).delete();
        } catch (Exception e) {
            log.warn("Failed to clear dirty flag: user_id={}", userId, e);
        }
    }
}
