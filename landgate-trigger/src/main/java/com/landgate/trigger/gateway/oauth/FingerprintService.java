package com.landgate.trigger.gateway.oauth;

import com.landgate.types.gateway.AnthropicApiProfile;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import com.landgate.types.gateway.GatewayHeaderPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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
    private static final Pattern USER_AGENT_VERSION_PATTERN =
            Pattern.compile("/(\\d+)\\.(\\d+)\\.(\\d+)");

    /**
     * 获取或创建客户端指纹。
     *
     * @param accountId     账号 ID
     * @param requestHeaders 客户端请求头
     * @return 指纹对象
     */
    public ClientFingerprint getOrCreateFingerprint(Long accountId, Map<String, String> requestHeaders) {
        ClientFingerprint cached = cache.get(accountId);
        if (cached == null) {
            ClientFingerprint created = createFingerprint(generateClientId(), requestHeaders);
            cache.put(accountId, created);
            return created;
        }

        String clientUserAgent = GatewayHeaderPolicy.value(requestHeaders, AnthropicApiProfile.HEADER_USER_AGENT);
        if (isNewerVersion(clientUserAgent, cached.getUserAgent())) {
            ClientFingerprint updated = mergeFingerprint(cached, requestHeaders, clientUserAgent);
            cache.put(accountId, updated);
            return updated;
        }
        return cached;
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
        headers.addAll(List.of(AnthropicApiProfile.HEADER_USER_AGENT, fp.getUserAgent()));
        headers.addAll(List.of(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_LANG, fp.getStainlessLang()));
        headers.addAll(List.of(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_PACKAGE_VERSION,
                fp.getStainlessPackageVersion()));
        headers.addAll(List.of(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_OS, fp.getStainlessOs()));
        headers.addAll(List.of(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_ARCH, fp.getStainlessArch()));
        headers.addAll(List.of(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_RUNTIME, fp.getStainlessRuntime()));
        headers.addAll(List.of(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_RUNTIME_VERSION,
                fp.getStainlessRuntimeVersion()));
        return headers;
    }

    public void applyFingerprint(HttpRequest.Builder builder, ClientFingerprint fp) {
        if (builder == null || fp == null) return;
        List<String> headers = applyFingerprint(fp);
        for (int i = 0; i + 1 < headers.size(); i += 2) {
            builder.setHeader(headers.get(i), headers.get(i + 1));
        }
    }

    private static ClientFingerprint createFingerprint(String clientId, Map<String, String> requestHeaders) {
        String userAgent = GatewayHeaderPolicy.value(requestHeaders, AnthropicApiProfile.HEADER_USER_AGENT);
        if (userAgent.isBlank()) {
            userAgent = ClaudeConstants.DEFAULT_CLAUDE_CLI_USER_AGENT;
        }
        return new ClientFingerprint(
                clientId,
                userAgent,
                fingerprintHeaderOrDefault(requestHeaders, AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_LANG),
                fingerprintHeaderOrDefault(requestHeaders,
                        AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_PACKAGE_VERSION),
                fingerprintHeaderOrDefault(requestHeaders, AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_OS),
                fingerprintHeaderOrDefault(requestHeaders, AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_ARCH),
                fingerprintHeaderOrDefault(requestHeaders, AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_RUNTIME),
                fingerprintHeaderOrDefault(requestHeaders,
                        AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_RUNTIME_VERSION));
    }

    private static ClientFingerprint mergeFingerprint(ClientFingerprint cached,
                                                      Map<String, String> requestHeaders,
                                                      String clientUserAgent) {
        return new ClientFingerprint(
                cached.getClientId(),
                clientUserAgent,
                fingerprintHeaderOrExisting(requestHeaders, AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_LANG,
                        cached.getStainlessLang()),
                fingerprintHeaderOrExisting(requestHeaders,
                        AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_PACKAGE_VERSION,
                        cached.getStainlessPackageVersion()),
                fingerprintHeaderOrExisting(requestHeaders, AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_OS,
                        cached.getStainlessOs()),
                fingerprintHeaderOrExisting(requestHeaders, AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_ARCH,
                        cached.getStainlessArch()),
                fingerprintHeaderOrExisting(requestHeaders, AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_RUNTIME,
                        cached.getStainlessRuntime()),
                fingerprintHeaderOrExisting(requestHeaders,
                        AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_RUNTIME_VERSION,
                        cached.getStainlessRuntimeVersion()));
    }

    private static String fingerprintHeaderOrDefault(Map<String, String> requestHeaders, String name) {
        String value = GatewayHeaderPolicy.value(requestHeaders, name);
        if (!value.isBlank()) {
            return value;
        }
        return AnthropicClaudeCodeProfile.DEFAULT_MIMICRY_HEADERS.getOrDefault(name, "");
    }

    private static String fingerprintHeaderOrExisting(Map<String, String> requestHeaders, String name, String existing) {
        String value = GatewayHeaderPolicy.value(requestHeaders, name);
        return value.isBlank() ? existing : value;
    }

    private static boolean isNewerVersion(String newUserAgent, String cachedUserAgent) {
        if (newUserAgent == null || newUserAgent.isBlank()
                || cachedUserAgent == null || cachedUserAgent.isBlank()) {
            return false;
        }
        String newProduct = productName(newUserAgent);
        String cachedProduct = productName(cachedUserAgent);
        if (newProduct.isBlank() || cachedProduct.isBlank() || !newProduct.equals(cachedProduct)) {
            return false;
        }
        int[] next = versionParts(newUserAgent);
        int[] current = versionParts(cachedUserAgent);
        if (next == null || current == null) {
            return false;
        }
        for (int i = 0; i < next.length; i++) {
            if (next[i] > current[i]) return true;
            if (next[i] < current[i]) return false;
        }
        return false;
    }

    private static String productName(String userAgent) {
        int slash = userAgent == null ? -1 : userAgent.indexOf('/');
        return slash <= 0 ? "" : userAgent.substring(0, slash).trim().toLowerCase(Locale.ROOT);
    }

    private static int[] versionParts(String userAgent) {
        var matcher = USER_AGENT_VERSION_PATTERN.matcher(userAgent);
        if (!matcher.find()) {
            return null;
        }
        try {
            return new int[]{
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            };
        } catch (NumberFormatException e) {
            return null;
        }
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
        private final String stainlessLang;
        private final String stainlessPackageVersion;
        private final String stainlessOs;
        private final String stainlessArch;
        private final String stainlessRuntime;
        private final String stainlessRuntimeVersion;
        private final String fingerprintHash;

        public ClientFingerprint(String clientId, String userAgent,
                                 String stainlessLang,
                                 String stainlessPackageVersion,
                                 String stainlessOs,
                                 String stainlessArch,
                                 String stainlessRuntime,
                                 String stainlessRuntimeVersion) {
            this.clientId = clientId;
            this.userAgent = userAgent;
            this.stainlessLang = stainlessLang;
            this.stainlessPackageVersion = stainlessPackageVersion;
            this.stainlessOs = stainlessOs;
            this.stainlessArch = stainlessArch;
            this.stainlessRuntime = stainlessRuntime;
            this.stainlessRuntimeVersion = stainlessRuntimeVersion;
            this.fingerprintHash = computeHash(clientId);
        }

        public String getClientId() { return clientId; }
        public String getUserAgent() { return userAgent; }
        public String getStainlessLang() { return stainlessLang; }
        public String getStainlessPackageVersion() { return stainlessPackageVersion; }
        public String getStainlessOs() { return stainlessOs; }
        public String getStainlessArch() { return stainlessArch; }
        public String getStainlessRuntime() { return stainlessRuntime; }
        public String getStainlessRuntimeVersion() { return stainlessRuntimeVersion; }
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
