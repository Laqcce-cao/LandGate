package com.landgate.infrastructure.verification;

import com.landgate.domain.auth.adapter.port.IVerificationCodePort;
import com.landgate.types.constant.RedisKeys;
import com.landgate.types.exception.AuthenticationException;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class VerificationCodeService implements IVerificationCodePort {

    private final RedissonClient redissonClient;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${landgate.verification.email-code-expire-seconds:300}")
    private long codeExpireSeconds;

    @Value("${landgate.verification.email-resend-cooldown-seconds:60}")
    private long resendCooldownSeconds;

    @Value("${landgate.verification.email-code-max-attempts:5}")
    private long maxAttempts;

    /** 生成6位数字验证码，存入Redis，返回验证码。若在冷却期内则抛出异常 */
    public String generateCode(String email, String purpose) {
        // 检查重发冷却期，不同业务场景互不影响。
        String cooldownKey = RedisKeys.emailCodeCooldownKey(purpose, email);
        RBucket<String> cooldownBucket = redissonClient.getBucket(cooldownKey);
        if (cooldownBucket.isExists()) {
            long ttl = cooldownBucket.remainTimeToLive();
            throw new AuthenticationException(
                    "Please wait " + (ttl / 1000) + " seconds before resending verification code");
        }

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        RBucket<String> bucket = redissonClient.getBucket(RedisKeys.emailCodeKey(purpose, email));
        bucket.set(code, Duration.ofSeconds(codeExpireSeconds));
        redissonClient.getBucket(RedisKeys.emailCodeAttemptsKey(purpose, email)).delete();

        // 设置重发冷却期
        cooldownBucket.set(email, Duration.ofSeconds(resendCooldownSeconds));

        return code;
    }

    /** 验证邮箱验证码，成功返回true并删除验证码和冷却期（一次性使用） */
    public boolean validateCode(String email, String code, String purpose) {
        RBucket<String> bucket = redissonClient.getBucket(RedisKeys.emailCodeKey(purpose, email));
        String storedCode = bucket.get();
        if (storedCode == null) {
            return false;
        }

        String attemptsKey = RedisKeys.emailCodeAttemptsKey(purpose, email);
        RAtomicLong attempts = redissonClient.getAtomicLong(attemptsKey);
        if (attempts.get() >= maxAttempts) {
            bucket.delete();
            attempts.delete();
            redissonClient.getBucket(RedisKeys.emailCodeCooldownKey(purpose, email)).delete();
            return false;
        }

        if (storedCode.equals(code)) {
            bucket.delete(); // 验证成功后删除验证码
            attempts.delete(); // 同时清除失败次数
            redissonClient.getBucket(RedisKeys.emailCodeCooldownKey(purpose, email)).delete(); // 同时清除冷却期
            return true;
        }

        long failedTimes = attempts.incrementAndGet();
        if (failedTimes == 1) {
            attempts.expire(Duration.ofSeconds(codeExpireSeconds));
        }
        if (failedTimes >= maxAttempts) {
            bucket.delete();
            attempts.delete();
            redissonClient.getBucket(RedisKeys.emailCodeCooldownKey(purpose, email)).delete();
        }
        return false;
    }
}
