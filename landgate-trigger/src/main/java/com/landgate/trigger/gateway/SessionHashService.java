package com.landgate.trigger.gateway;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话粘滞服务 —— 基于客户端 IP + User-Agent + API Key 的 SHA-256 哈希实现会话保持。
 * <p>
 * 同一会话的请求优先路由到上次使用的上游账号，减少账号切换。
 */
@Slf4j
@Component
public class SessionHashService {

    private final Map<String, Long> sessionCache = new ConcurrentHashMap<>();

    public String generateHash(HttpServletRequest request, Long apiKeyId) {
        String clientIp = request.getRemoteAddr();
        String userAgent = normalizeUserAgent(request.getHeader("User-Agent"));
        String raw = clientIp + "|" + userAgent + "|" + apiKeyId;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    public Long getBoundAccount(String sessionHash) {
        return sessionCache.get(sessionHash);
    }

    public void bindSession(String sessionHash, Long accountId) {
        sessionCache.put(sessionHash, accountId);
        log.debug("Session bound: hash={}, account_id={}", sessionHash, accountId);
    }

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
