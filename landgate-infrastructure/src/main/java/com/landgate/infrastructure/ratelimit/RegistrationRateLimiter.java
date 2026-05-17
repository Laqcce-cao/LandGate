package com.landgate.infrastructure.ratelimit;

import com.landgate.types.constant.RedisKeys;
import com.landgate.types.exception.AuthenticationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class RegistrationRateLimiter {

    private final RedissonClient redissonClient;

    @Value("${landgate.ratelimit.registration.max-per-ip-per-hour:3}")
    private int maxPerIpPerHour;

    public void checkRateLimit(String ip) {
        String key = RedisKeys.registerRateKey(ip);
        RAtomicLong counter = redissonClient.getAtomicLong(key);
        long count = counter.incrementAndGet();
        if (count == 1) {
            counter.expire(Duration.ofHours(1));
        }
        if (count > maxPerIpPerHour) {
            log.warn("Registration rate limit exceeded for IP: {}, count: {}", ip, count);
            throw new AuthenticationException("Too many registration attempts. Please try again later.");
        }
    }
}
