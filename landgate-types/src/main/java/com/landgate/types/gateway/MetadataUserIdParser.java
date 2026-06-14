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
    public static final String FIELD_DEVICE_ID = "device_id";
    public static final String FIELD_ACCOUNT_UUID = "account_uuid";
    public static final String FIELD_SESSION_ID = "session_id";
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
            String deviceId = root.has(FIELD_DEVICE_ID) ? root.get(FIELD_DEVICE_ID).asText() : null;
            String accountUuid = root.has(FIELD_ACCOUNT_UUID) ? root.get(FIELD_ACCOUNT_UUID).asText() : null;
            String sessionId = root.has(FIELD_SESSION_ID) ? root.get(FIELD_SESSION_ID).asText() : null;
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
            obj.put(FIELD_DEVICE_ID, deviceId != null ? deviceId : "");
            obj.put(FIELD_ACCOUNT_UUID, accountUuid != null ? accountUuid : "");
            obj.put(FIELD_SESSION_ID, sessionId != null ? sessionId : UUID.randomUUID().toString());
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
