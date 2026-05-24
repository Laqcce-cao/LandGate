package com.landgate.trigger.gateway;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * 会话粘滞服务 —— 基于客户端 IP + User-Agent + API Key 的 SHA-256 哈希实现会话保持。
 * <p>
 * 使用 Redis {@link RMapCache} 存储会话绑定，支持多实例共享和 TTL 自动过期。
 * 每次读取时刷新 TTL（滑动过期），活跃会话的粘性绑定持续有效。
 */
@Slf4j
@Component
public class SessionHashService {

    private static final long TTL_HOURS = 1;
    private static final String CACHE_KEY = "session:sticky";

    private final RMapCache<String, Long> sessionCache;

    public SessionHashService(RedissonClient redissonClient) {
        this.sessionCache = redissonClient.getMapCache(CACHE_KEY);
    }

    /**
     * 生成会话哈希 —— 基于客户端 IP、归一化 User-Agent、API Key ID。
     * <p>
     * 同一 API Key 从同一客户端发起的请求视为同一会话，优先分配到同一上游账号。
     * 一个 API Key 对应一个应用，通常不会频繁切换模型，因此不需要模型级别的隔离。
     *
     * @param request  HTTP 请求
     * @param apiKeyId API Key ID
     * @return 16 位十六进制会话哈希
     */
    public String generateHash(HttpServletRequest request, Long apiKeyId) {
        String clientIp = request.getRemoteAddr();
        String userAgent = normalizeUserAgent(request.getHeader("User-Agent"));
        String raw = new StringBuilder()
                .append(clientIp).append('|').append(userAgent).append('|').append(apiKeyId)
                .toString();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    /**
     * 获取会话绑定的上游账号 ID，并刷新 TTL。
     * <p>
     * 每次命中时重新设置过期时间（滑动过期），
     * 保证活跃会话的粘性绑定不会因固定 TTL 而过期。
     *
     * @param sessionHash 会话哈希
     * @return 绑定的账号 ID，未绑定时返回 null
     */
    public Long getBoundAccount(String sessionHash) {
        Long accountId = sessionCache.get(sessionHash);
        if (accountId != null) {
            // 滑动过期：每次读取刷新 TTL
            sessionCache.put(sessionHash, accountId, TTL_HOURS, TimeUnit.HOURS);
            log.debug("Session TTL refreshed: hash={}, account_id={}", sessionHash, accountId);
        }
        return accountId;
    }

    /**
     * 绑定会话到上游账号，设置 1 小时 TTL。
     *
     * @param sessionHash 会话哈希
     * @param accountId   上游账号 ID
     */
    public void bindSession(String sessionHash, Long accountId) {
        sessionCache.put(sessionHash, accountId, TTL_HOURS, TimeUnit.HOURS);
        log.debug("Session bound: hash={}, account_id={}", sessionHash, accountId);
    }

    /**
     * 归一化 User-Agent —— 将版本号替换为占位符，
     * 避免客户端版本升级导致粘性断裂。
     */
    private String normalizeUserAgent(String ua) {
        if (ua == null) return "";
        return ua.replaceAll("\\d+\\.\\d+\\.\\d+", "X.Y.Z");
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
