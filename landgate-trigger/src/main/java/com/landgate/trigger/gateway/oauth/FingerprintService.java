package com.landgate.trigger.gateway.oauth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端指纹管理服务。
 * <p>
 * 从客户端请求头提取指纹标识，用于追踪和伪装过程中的一致性。
 * 参照：sub2api {@code identity_service.go} 的 fingerprint 相关函数。
 */
@Slf4j
@Component
public class FingerprintService {

    /** 内存缓存：accountId → ClientFingerprint（简化实现，无 Redis） */
    private final Map<Long, ClientFingerprint> cache = new ConcurrentHashMap<>();

    /**
     * 获取或创建客户端指纹。
     *
     * @param accountId     账号 ID
     * @param requestHeaders 客户端请求头
     * @return 指纹对象
     */
    public ClientFingerprint getOrCreateFingerprint(Long accountId, Map<String, String> requestHeaders) {
        return cache.computeIfAbsent(accountId, id -> {
            String userAgent = requestHeaders != null
                    ? requestHeaders.getOrDefault("User-Agent", "claude-cli/" + ClaudeConstants.CLI_CURRENT_VERSION)
                    : "claude-cli/" + ClaudeConstants.CLI_CURRENT_VERSION;
            String clientId = generateClientId();
            return new ClientFingerprint(clientId, userAgent);
        });
    }

    /**
     * 获取已有指纹（不创建）。
     */
    public ClientFingerprint getFingerprint(Long accountId) {
        return cache.get(accountId);
    }

    /**
     * 应用指纹到上游请求头。
     */
    public List<String> applyFingerprint(ClientFingerprint fp) {
        if (fp == null) return List.of();
        List<String> headers = new ArrayList<>();
        headers.addAll(List.of("User-Agent", fp.getUserAgent()));
        return headers;
    }

    private String generateClientId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ========================
    // 数据类
    // ========================

    public static class ClientFingerprint {
        private final String clientId;
        private final String userAgent;
        private final String fingerprintHash;

        public ClientFingerprint(String clientId, String userAgent) {
            this.clientId = clientId;
            this.userAgent = userAgent;
            this.fingerprintHash = computeHash(clientId);
        }

        public String getClientId() { return clientId; }
        public String getUserAgent() { return userAgent; }
        public String getFingerprintHash() { return fingerprintHash; }

        /**
         * 计算指纹哈希（用于 billing header 中的 {fp} 部分）。
         * SHA256(salt + clientId)，取前 3 个 hex 字符。
         */
        private static String computeHash(String clientId) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                String input = ClaudeConstants.FINGERPRINT_SALT + clientId;
                byte[] hash = md.digest(input.getBytes());
                int val = ((hash[0] & 0xFF) << 8 | (hash[1] & 0xFF)) & 0xFFF;
                return String.format("%03x", val);
            } catch (Exception e) {
                return "000";
            }
        }
    }
}
