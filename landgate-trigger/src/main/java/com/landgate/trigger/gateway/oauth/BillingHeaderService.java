package com.landgate.trigger.gateway.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Billing 头服务 —— billing attribution block 构建、版本同步、CCH xxHash64 签名。
 * <p>
 * 参照：sub2api {@code gateway_billing_block.go} + {@code gateway_billing_header.go}
 */
@Slf4j
@Component
public class BillingHeaderService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** cc_version 版本号正则（匹配 X.Y.Z 三段 semver） */
    private static final Pattern CC_VERSION_PATTERN = Pattern.compile("cc_version=\\d+\\.\\d+\\.\\d+");

    /** CCH 占位符正则（限定在 x-anthropic-billing-header 上下文中） */
    private static final Pattern CCH_PLACEHOLDER_PATTERN =
            Pattern.compile("(x-anthropic-billing-header:[^\"]*?\\bcch=)(00000)(;)");

    /** xxHash64 seed（与 sub2api 一致） */
    private static final long CCH_SEED = 0x6E52736AC806831EL;

    /**
     * 构造 billing attribution block JSON。
     * <p>
     * 格式严格对齐真实 Claude Code CLI：
     * <pre>
     * {"type":"text","text":"x-anthropic-billing-header: cc_version=2.1.92.{fp}; cc_entrypoint=cli; cch=00000;"}
     * </pre>
     * cch=00000 是签名占位符，由 {@link #signBillingHeaderCCH} 替换。
     *
     * @param body       Anthropic 请求 body（用于计算指纹）
     * @param cliVersion CLI 版本号
     * @return billing block JSON 字符串
     */
    public String buildBillingAttributionBlockJSON(String body, String cliVersion) {
        if (cliVersion == null || cliVersion.isEmpty()) {
            throw new IllegalArgumentException("cliVersion required");
        }
        String fp = computeClaudeCodeFingerprint(body, cliVersion);
        String text = String.format(
                "x-anthropic-billing-header: cc_version=%s.%s; cc_entrypoint=cli; cch=00000;",
                cliVersion, fp);
        try {
            var node = JSON.createObjectNode();
            node.put("type", "text");
            node.put("text", text);
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            log.error("Failed to build billing attribution block", e);
            return null;
        }
    }

    /**
     * 计算 Claude Code 指纹。
     * <p>
     * 算法对齐真实 CLI：
     * 1. 取 messages 中第一条 role=user 的纯文本
     * 2. 取第 4、7、20 个字符（不足以 '0' 补齐）
     * 3. SHA256(salt + chars + version) 取前 3 个 hex 字符
     *
     * @param body    Anthropic 请求 body
     * @param version CLI 版本号
     * @return 3 位 hex 指纹
     */
    public String computeClaudeCodeFingerprint(String body, String version) {
        String firstText = extractFirstUserText(body);
        int[] indices = {4, 7, 20};
        StringBuilder chars = new StringBuilder(3);
        for (int i : indices) {
            if (i < firstText.length()) {
                chars.append(firstText.charAt(i));
            } else {
                chars.append('0');
            }
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String input = ClaudeConstants.FINGERPRINT_SALT + chars + version;
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return String.format("%02x%02x", hash[0], hash[1]).substring(0, 3);
        } catch (Exception e) {
            return "000";
        }
    }

    /**
     * 同步 billing header 中的 cc_version 为实际 UA 版本。
     * <p>
     * 仅修改 system 数组中以 "x-anthropic-billing-header" 开头的 text block。
     *
     * @param body      Anthropic 请求 body
     * @param userAgent 客户端 User-Agent（从中提取版本号）
     * @return 修改后的 body
     */
    public String syncBillingHeaderVersion(String body, String userAgent) {
        String version = extractVersion(userAgent);
        if (version == null || version.isEmpty()) return body;

        try {
            JsonNode root = JSON.readTree(body);
            if (!root.has("system")) return body;
            JsonNode system = root.get("system");
            if (!system.isArray()) return body;

            String replacement = "cc_version=" + version;
            boolean modified = false;

            for (int i = 0; i < system.size(); i++) {
                JsonNode item = system.get(i);
                if (item.has("text") && item.get("text").isTextual()) {
                    String text = item.get("text").asText();
                    if (text != null && text.startsWith("x-anthropic-billing-header")) {
                        String newText = CC_VERSION_PATTERN.matcher(text).replaceAll(replacement);
                        if (!newText.equals(text)) {
                            body = replaceJsonPathValue(body, "system", i, "text", text, newText);
                            modified = true;
                        }
                    }
                }
            }
            return modified ? body : body;
        } catch (Exception e) {
            log.debug("Failed to sync billing header version: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 对完整 body 计算 xxHash64 签名，替换 cch=00000 占位符。
     * <p>
     * CCH 签名必须是最后一步 body 修改操作！签名覆盖整个 body，顺序错误会导致签名不匹配。
     *
     * @param body 包含 cch=00000 占位符的完整请求 body
     * @return 签名后的 body
     */
    public String signBillingHeaderCCH(String body) {
        if (!CCH_PLACEHOLDER_PATTERN.matcher(body).find()) return body;

        long hash = xxHash64(body.getBytes(StandardCharsets.UTF_8), CCH_SEED);
        String cch = String.format("%05x", hash & 0xFFFFFL);

        return CCH_PLACEHOLDER_PATTERN.matcher(body).replaceAll("$1" + cch + "$3");
    }

    // ========================
    // 辅助方法
    // ========================

    /**
     * 提取 messages 中第一条 user 消息的首段 text 内容。
     * 兼容 string 和 []block 两种 content 格式。
     */
    static String extractFirstUserText(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.has("messages") || !root.get("messages").isArray()) return "";
            JsonNode messages = root.get("messages");
            for (JsonNode msg : messages) {
                if (!"user".equals(msg.has("role") ? msg.get("role").asText() : "")) continue;
                JsonNode content = msg.get("content");
                if (content == null) continue;
                if (content.isTextual()) return content.asText();
                if (content.isArray()) {
                    for (JsonNode block : content) {
                        if ("text".equals(block.has("type") ? block.get("type").asText() : "")
                                && block.has("text")) {
                            return block.get("text").asText();
                        }
                    }
                }
                return "";
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    /**
     * 从 User-Agent 中提取 CLI 版本号。
     */
    private static String extractVersion(String ua) {
        if (ua == null) return "";
        java.util.regex.Matcher m = ClaudeConstants.CLAUDE_CLI_UA_PATTERN.matcher(ua);
        if (m.find()) {
            String matched = m.group();
            int slash = matched.indexOf('/');
            if (slash >= 0) return matched.substring(slash + 1);
        }
        return "";
    }

    /**
     * 替换 JSON body 中指定路径的字段值（字符串级别替换，避免重新序列化）。
     */
    private static String replaceJsonPathValue(String body, String arrayField, int index,
                                                String subField, String oldValue, String newValue) {
        // 匹配 "system.0.text" 中的值并替换
        String path = "\"" + arrayField + "\":[";
        int start = body.indexOf(path);
        if (start < 0) return body;

        // 找到对应索引的元素
        int depth = 0;
        int pos = start + path.length();
        int currentIndex = 0;
        while (pos < body.length() && currentIndex <= index) {
            char c = body.charAt(pos);
            if (c == '{') {
                if (currentIndex == index) {
                    // 在目标对象中查找并替换 text 字段
                    String searchKey = "\"" + subField + "\":\"" + oldValue + "\"";
                    String replaceKey = "\"" + subField + "\":\"" + newValue + "\"";
                    if (body.substring(pos).contains(searchKey)) {
                        return body.replace(searchKey, replaceKey);
                    }
                    return body;
                }
                depth++;
                // 跳过整个对象
                int objEnd = findMatchingBrace(body, pos);
                if (objEnd > pos) pos = objEnd;
            }
            if (c == ',' && depth == 0) {
                currentIndex++;
            }
            pos++;
        }
        return body;
    }

    /** 找到匹配的 } 括号位置 */
    private static int findMatchingBrace(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '{') depth++;
            else if (s.charAt(i) == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return -1;
    }

    // ========================
    // xxHash64 纯 Java 实现
    // ========================

    private static final long PRIME64_1 = 0x9E3779B185EBCA87L;
    private static final long PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
    private static final long PRIME64_3 = 0x165667B19E3779F9L;
    private static final long PRIME64_4 = 0x85EBCA77C2B2AE63L;
    private static final long PRIME64_5 = 0x27D4EB2F165667C5L;

    /**
     * 计算数据的 xxHash64（使用指定 seed）。
     */
    private static long xxHash64(byte[] data, long seed) {
        int len = data.length;
        int offset = 0;
        long h64;

        if (len >= 32) {
            long v1 = seed + PRIME64_1 + PRIME64_2;
            long v2 = seed + PRIME64_2;
            long v3 = seed;
            long v4 = seed - PRIME64_1;

            int limit = len - 32;
            while (offset <= limit) {
                v1 = round(v1, readLE64(data, offset)); offset += 8;
                v2 = round(v2, readLE64(data, offset)); offset += 8;
                v3 = round(v3, readLE64(data, offset)); offset += 8;
                v4 = round(v4, readLE64(data, offset)); offset += 8;
            }

            h64 = rotl64(v1, 1) + rotl64(v2, 7) + rotl64(v3, 12) + rotl64(v4, 18);
            h64 = mergeRound(h64, v1);
            h64 = mergeRound(h64, v2);
            h64 = mergeRound(h64, v3);
            h64 = mergeRound(h64, v4);
        } else {
            h64 = seed + PRIME64_5;
        }

        h64 += len;

        // 处理剩余字节（每次 8 字节）
        int remainingStart = offset;
        while (offset + 8 <= len) {
            long k1 = round(0, readLE64(data, offset));
            h64 ^= k1;
            h64 = rotl64(h64, 27) * PRIME64_1 + PRIME64_4;
            offset += 8;
        }

        // 处理剩余字节（每次 4 字节）
        if (offset + 4 <= len) {
            h64 ^= (readLE32(data, offset) & 0xFFFFFFFFL) * PRIME64_1;
            h64 = rotl64(h64, 23) * PRIME64_2 + PRIME64_3;
            offset += 4;
        }

        // 处理最后 1-3 字节
        while (offset < len) {
            h64 ^= (data[offset] & 0xFFL) * PRIME64_5;
            h64 = rotl64(h64, 11) * PRIME64_1;
            offset++;
        }

        // 雪崩最终化
        return avalanche(h64);
    }

    private static long round(long acc, long input) {
        acc += input * PRIME64_2;
        acc = rotl64(acc, 31);
        acc *= PRIME64_1;
        return acc;
    }

    private static long mergeRound(long acc, long val) {
        acc ^= round(0, val);
        acc = acc * PRIME64_1 + PRIME64_4;
        return acc;
    }

    private static long rotl64(long v, int n) {
        return (v << n) | (v >>> (64 - n));
    }

    private static long readLE64(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24)
                | ((data[offset + 4] & 0xFFL) << 32)
                | ((data[offset + 5] & 0xFFL) << 40)
                | ((data[offset + 6] & 0xFFL) << 48)
                | ((data[offset + 7] & 0xFFL) << 56);
    }

    private static int readLE32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static long avalanche(long h64) {
        h64 ^= h64 >>> 33;
        h64 *= PRIME64_2;
        h64 ^= h64 >>> 29;
        h64 *= PRIME64_3;
        h64 ^= h64 >>> 32;
        return h64;
    }
}
