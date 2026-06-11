package com.landgate.trigger.gateway.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * metadata.user_id 解析器 —— 支持两种格式。
 * <p>
 * Legacy 格式：{@code user_{64hex_device_id}_account_{uuid}_session_{36char-uuid}}<br>
 * JSON 格式（Claude Code >= 2.1.78）：{@code {"device_id":"...","account_uuid":"...","session_id":"..."}}
 * <p>
 * 参照：sub2api {@code metadata_userid.go}
 */
@Slf4j
public class MetadataUserIdParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Legacy 格式正则 */
    private static final Pattern LEGACY_PATTERN = Pattern.compile(
            "^user_([a-fA-F0-9]{64})_account_([a-fA-F0-9-]*)_session_([a-fA-F0-9-]{36})$");

    /**
     * 解析 metadata.user_id，先尝试 JSON 格式，失败回退到 legacy 正则。
     *
     * @return 解析结果，解析失败返回 null
     */
    public static ParsedMetadataUserId parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;

        // JSON 格式（以 { 开头）
        if (raw.startsWith("{")) {
            return parseJson(raw);
        }
        // Legacy 格式
        return parseLegacy(raw);
    }

    private static ParsedMetadataUserId parseJson(String raw) {
        try {
            JsonNode root = JSON.readTree(raw);
            String deviceId = root.has("device_id") ? root.get("device_id").asText() : null;
            String accountUuid = root.has("account_uuid") ? root.get("account_uuid").asText() : null;
            String sessionId = root.has("session_id") ? root.get("session_id").asText() : null;

            // device_id 和 session_id 必须非空
            if (deviceId == null || deviceId.isEmpty() || sessionId == null || sessionId.isEmpty()) {
                return null;
            }
            return new ParsedMetadataUserId(deviceId, accountUuid, sessionId, Format.JSON);
        } catch (Exception e) {
            log.debug("Failed to parse JSON metadata.user_id: {}", e.getMessage());
            return null;
        }
    }

    private static ParsedMetadataUserId parseLegacy(String raw) {
        Matcher m = LEGACY_PATTERN.matcher(raw);
        if (!m.matches()) return null;
        return new ParsedMetadataUserId(m.group(1), m.group(2), m.group(3), Format.LEGACY);
    }

    /**
     * 格式化 metadata.user_id。根据 UA 版本决定输出格式。
     *
     * @param deviceId    设备 ID
     * @param accountUuid 账号 UUID
     * @param sessionId   会话 ID
     * @param cliVersion  CLI 版本号（用于判断输出格式：>= 2.1.78 用 JSON）
     */
    public static String format(String deviceId, String accountUuid, String sessionId, String cliVersion) {
        if (shouldUseJsonFormat(cliVersion)) {
            return formatJson(deviceId, accountUuid, sessionId);
        }
        return formatLegacy(deviceId, accountUuid, sessionId);
    }

    private static boolean shouldUseJsonFormat(String version) {
        if (version == null) return true; // 默认使用 JSON
        try {
            String[] parts = version.split("\\.");
            if (parts.length < 3) return true;
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);
            // >= 2.1.78
            return major > 2 || (major == 2 && (minor > 1 || (minor == 1 && patch >= 78)));
        } catch (Exception e) {
            return true;
        }
    }

    private static String formatJson(String deviceId, String accountUuid, String sessionId) {
        try {
            var obj = JSON.createObjectNode();
            obj.put("device_id", deviceId != null ? deviceId : "");
            obj.put("account_uuid", accountUuid != null ? accountUuid : "");
            obj.put("session_id", sessionId != null ? sessionId : UUID.randomUUID().toString());
            return JSON.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String formatLegacy(String deviceId, String accountUuid, String sessionId) {
        return String.format("user_%s_account_%s_session_%s",
                deviceId != null ? deviceId : "0".repeat(64),
                accountUuid != null ? accountUuid : "",
                sessionId != null ? sessionId : UUID.randomUUID().toString());
    }

    /** 从 body 中提取 metadata.user_id */
    public static String extractFromBody(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has("metadata") && root.get("metadata").has("user_id")) {
                return root.get("metadata").get("user_id").asText();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    // ========================
    // 数据类
    // ========================

    public enum Format { LEGACY, JSON }

    public record ParsedMetadataUserId(
            String deviceId,
            String accountUuid,
            String sessionId,
            Format format
    ) {}
}
