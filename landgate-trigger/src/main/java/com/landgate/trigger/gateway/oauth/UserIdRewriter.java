package com.landgate.trigger.gateway.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.MetadataUserIdParser;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * metadata.user_id 重写服务。
 * <p>
 * 参照：sub2api {@code identity_service.go} 的 RewriteUserID / RewriteUserIDWithMasking。
 */
@Slf4j
@Component
public class UserIdRewriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 重写 body 中的 metadata.user_id。
     * <p>
     * 解析现有 user_id → 提取 session_id → 生成新 session hash →
     * 用新的 deviceId + accountUUID + sessionHash 重建 user_id。
     *
     * @param body             Anthropic 请求 body
     * @param accountId        账号 ID
     * @param accountUUID      账号 UUID
     * @param cachedClientId   缓存的客户端 ID（来自指纹）
     * @param fingerprintUA    指纹中的 User-Agent（用于判断输出格式版本）
     * @return 修改后的 body（如果无需修改则返回原 body）
     */
    public String rewriteUserID(String body, Long accountId, String accountUUID,
                                 String cachedClientId, String fingerprintUA) {
        if (body == null || body.isEmpty() || accountUUID == null || cachedClientId == null) {
            return body;
        }

        try {
            JsonNode root = JSON.readTree(body);
            if (!root.has(AnthropicMessagesBodyPolicy.FIELD_METADATA)) return body;
            JsonNode metadata = root.get(AnthropicMessagesBodyPolicy.FIELD_METADATA);
            if (metadata == null || !metadata.has(AnthropicMessagesBodyPolicy.FIELD_USER_ID)) return body;
            String userId = metadata.get(AnthropicMessagesBodyPolicy.FIELD_USER_ID).asText();
            if (userId == null || userId.isEmpty()) return body;

            // 解析 user_id
            MetadataUserIdParser.ParsedMetadataUserId parsed = MetadataUserIdParser.parse(userId);
            if (parsed == null) return body;

            String sessionTail = parsed.sessionId();

            // 生成新的 session hash: SHA256(accountId::sessionTail) → UUID 格式
            String seed = accountId + "::" + sessionTail;
            String newSessionHash = generateUUIDFromSeed(seed);

            // 根据客户端版本选择输出格式
            String uaVersion = extractVersion(fingerprintUA);
            String newUserId = MetadataUserIdParser.format(
                    cachedClientId, accountUUID, newSessionHash, uaVersion);

            if (newUserId.equals(userId)) return body;

            // 替换 metadata.user_id
            return replaceMetadataUserId(root, newUserId);
        } catch (Exception e) {
            log.debug("Failed to rewrite user_id for account {}: {}", accountId, e.getMessage());
            return body;
        }
    }

    /**
     * 重写 metadata.user_id（含 session ID 伪装）。
     * <p>
     * 当前简化实现：执行基础 rewriteUserID（session_id_masking 需要 Redis 支持，暂不实现）。
     *
     * @param body             Anthropic 请求 body
     * @param account          账号实体
     * @param accountUUID      账号 UUID
     * @param cachedClientId   缓存的客户端 ID
     * @param fingerprintUA    指纹 UA
     * @return 修改后的 body
     */
    public String rewriteUserIDWithMasking(String body, AccountEntity account, String accountUUID,
                                            String cachedClientId, String fingerprintUA) {
        return rewriteUserID(body, account.getId(), accountUUID, cachedClientId, fingerprintUA);
    }

    /**
     * 从已重写的 body 中提取当前的 session_id。
     * <p>
     * 用于同步 X-Claude-Code-Session-Id 请求头。
     */
    public String extractCurrentSessionId(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has(AnthropicMessagesBodyPolicy.FIELD_METADATA)
                    && root.get(AnthropicMessagesBodyPolicy.FIELD_METADATA)
                    .has(AnthropicMessagesBodyPolicy.FIELD_USER_ID)) {
                String userId = root.get(AnthropicMessagesBodyPolicy.FIELD_METADATA)
                        .get(AnthropicMessagesBodyPolicy.FIELD_USER_ID)
                        .asText();
                MetadataUserIdParser.ParsedMetadataUserId parsed = MetadataUserIdParser.parse(userId);
                if (parsed != null) return parsed.sessionId();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * 从 seed 生成 UUID 格式的字符串。
     * SHA256(seed) → 取前 32 hex 字符 → 格式化为 8-4-4-4-12 UUID 形态。
     */
    private static String generateUUIDFromSeed(String seed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            String h = hex.toString();
            return h.substring(0, 8) + "-" + h.substring(8, 12) + "-"
                    + h.substring(12, 16) + "-" + h.substring(16, 20) + "-" + h.substring(20, 32);
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    /**
     * 从 User-Agent 中提取 CLI 版本号。
     */
    private static String extractVersion(String ua) {
        if (ua == null) return "";
        java.util.regex.Matcher m = AnthropicClaudeCodeProfile.CLAUDE_CLI_UA_PATTERN.matcher(ua);
        if (m.find()) {
            String matched = m.group();
            int slash = matched.indexOf('/');
            if (slash >= 0) return matched.substring(slash + 1);
        }
        return "";
    }

    /**
     * 在解析后的 body 中替换 metadata.user_id 值。
     */
    private static String replaceMetadataUserId(JsonNode root, String newUserId) {
        if (!root.isObject()) {
            return root.toString();
        }
        JsonNode metadata = root.get(AnthropicMessagesBodyPolicy.FIELD_METADATA);
        if (metadata != null && metadata.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) metadata)
                    .put(AnthropicMessagesBodyPolicy.FIELD_USER_ID, newUserId);
        }
        return root.toString();
    }
}
