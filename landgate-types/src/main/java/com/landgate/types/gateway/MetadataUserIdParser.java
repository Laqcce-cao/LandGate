package com.landgate.types.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * metadata.user_id parser with Claude Code legacy and JSON formats.
 *
 * <p>Aligned with Sub2API metadata_userid behavior.</p>
 */
public final class MetadataUserIdParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LEGACY_PATTERN = Pattern.compile(
            "^user_([a-fA-F0-9]{64})_account_([a-fA-F0-9-]*)_session_([a-fA-F0-9-]{36})$");

    private MetadataUserIdParser() {
    }

    public static ParsedMetadataUserId parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (raw.startsWith("{")) {
            return parseJson(raw);
        }
        return parseLegacy(raw);
    }

    private static ParsedMetadataUserId parseJson(String raw) {
        try {
            JsonNode root = JSON.readTree(raw);
            String deviceId = root.has("device_id") ? root.get("device_id").asText() : null;
            String accountUuid = root.has("account_uuid") ? root.get("account_uuid").asText() : null;
            String sessionId = root.has("session_id") ? root.get("session_id").asText() : null;
            if (deviceId == null || deviceId.isEmpty() || sessionId == null || sessionId.isEmpty()) {
                return null;
            }
            return new ParsedMetadataUserId(deviceId, accountUuid, sessionId, Format.JSON);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ParsedMetadataUserId parseLegacy(String raw) {
        Matcher m = LEGACY_PATTERN.matcher(raw);
        if (!m.matches()) return null;
        return new ParsedMetadataUserId(m.group(1), m.group(2), m.group(3), Format.LEGACY);
    }

    public static String format(String deviceId, String accountUuid, String sessionId, String cliVersion) {
        if (shouldUseJsonFormat(cliVersion)) {
            return formatJson(deviceId, accountUuid, sessionId);
        }
        return formatLegacy(deviceId, accountUuid, sessionId);
    }

    public static boolean shouldUseJsonFormat(String version) {
        if (version == null) return true;
        try {
            String[] parts = version.split("\\.");
            if (parts.length < 3) return true;
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);
            return major > 2 || (major == 2 && (minor > 1 || (minor == 1 && patch >= 78)));
        } catch (Exception ignored) {
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
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private static String formatLegacy(String deviceId, String accountUuid, String sessionId) {
        return String.format("user_%s_account_%s_session_%s",
                deviceId != null ? deviceId : "0".repeat(64),
                accountUuid != null ? accountUuid : "",
                sessionId != null ? sessionId : UUID.randomUUID().toString());
    }

    public static String extractFromBody(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has(AnthropicMessagesBodyPolicy.FIELD_METADATA)
                    && root.get(AnthropicMessagesBodyPolicy.FIELD_METADATA)
                    .has(AnthropicMessagesBodyPolicy.FIELD_USER_ID)) {
                return root.get(AnthropicMessagesBodyPolicy.FIELD_METADATA)
                        .get(AnthropicMessagesBodyPolicy.FIELD_USER_ID)
                        .asText();
            }
        } catch (Exception ignored) {
            // ignore malformed bodies
        }
        return null;
    }

    public enum Format { LEGACY, JSON }

    public record ParsedMetadataUserId(
            String deviceId,
            String accountUuid,
            String sessionId,
            Format format
    ) {}
}
