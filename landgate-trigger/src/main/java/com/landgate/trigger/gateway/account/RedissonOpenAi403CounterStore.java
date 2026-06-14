package com.landgate.trigger.gateway.account;

import com.landgate.types.constant.RedisKeys;
import com.landgate.types.gateway.OpenAiUpstreamErrorPolicy;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redisson-backed OpenAI 403 counter.
 */
@Component
public class RedissonOpenAi403CounterStore implements OpenAi403CounterStore {

    private final RedissonClient redissonClient;

    public RedissonOpenAi403CounterStore(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public long increment(Long accountId) {
        RAtomicLong counter = redissonClient.getAtomicLong(RedisKeys.openAi403CounterKey(accountId));
        long count = counter.incrementAndGet();
        counter.expire(Duration.ofMinutes(OpenAiUpstreamErrorPolicy.FORBIDDEN_COUNTER_WINDOW_MINUTES));
        return count;
    }

    @Override
    public void reset(Long accountId) {
        redissonClient.getAtomicLong(RedisKeys.openAi403CounterKey(accountId)).delete();
    }
}
