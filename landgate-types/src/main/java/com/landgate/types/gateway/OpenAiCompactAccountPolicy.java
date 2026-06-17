package com.landgate.types.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.types.enums.Platform;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sub2API-compatible OpenAI /responses/compact account policy.
 *
 * <p>This policy owns only compact account capability and compact-only model
 * mapping facts. It must not choose accounts, build routes, normalize request
 * bodies, build auth headers, or translate protocols.</p>
 */
public final class OpenAiCompactAccountPolicy {

    public static final String MODE_AUTO = "auto";
    public static final String MODE_FORCE_ON = "force_on";
    public static final String MODE_FORCE_OFF = "force_off";

    public static final String EXTRA_OPENAI_COMPACT_MODE = "openai_compact_mode";
    public static final String EXTRA_OPENAI_COMPACT_SUPPORTED = "openai_compact_supported";
    public static final String CREDENTIAL_COMPACT_MODEL_MAPPING = "compact_model_mapping";
    public static final int UNSUPPORTED_STATUS = 503;
    public static final String UNSUPPORTED_CODE = "compact_not_supported";
    public static final String UNSUPPORTED_MESSAGE = "No available OpenAI accounts support /responses/compact";

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiCompactAccountPolicy() {
    }

    public static String normalizeMode(String mode) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase();
        return switch (normalized) {
            case MODE_FORCE_ON -> MODE_FORCE_ON;
            case MODE_FORCE_OFF -> MODE_FORCE_OFF;
            default -> MODE_AUTO;
        };
    }

    public static String compactMode(Platform platform, String extraJson) {
        if (platform != Platform.OPENAI) {
            return MODE_AUTO;
        }
        JsonNode root = parseObject(extraJson);
        if (root == null) {
            return MODE_AUTO;
        }
        JsonNode mode = root.get(EXTRA_OPENAI_COMPACT_MODE);
        return mode != null && mode.isTextual() ? normalizeMode(mode.asText()) : MODE_AUTO;
    }

    public static CompactSupport compactSupportKnown(Platform platform, String extraJson) {
        if (platform != Platform.OPENAI) {
            return new CompactSupport(false, false);
        }
        String mode = compactMode(platform, extraJson);
        if (MODE_FORCE_ON.equals(mode)) {
            return new CompactSupport(true, true);
        }
        if (MODE_FORCE_OFF.equals(mode)) {
            return new CompactSupport(false, true);
        }

        JsonNode root = parseObject(extraJson);
        if (root == null) {
            return new CompactSupport(false, false);
        }
        JsonNode supported = root.get(EXTRA_OPENAI_COMPACT_SUPPORTED);
        if (supported == null || !supported.isBoolean()) {
            return new CompactSupport(false, false);
        }
        return new CompactSupport(supported.asBoolean(), true);
    }

    public static boolean allowsCompact(Platform platform, String extraJson) {
        if (platform != Platform.OPENAI) {
            return false;
        }
        CompactSupport support = compactSupportKnown(platform, extraJson);
        return !support.known() || support.supported();
    }

    /**
     * Returns Sub2API compact support tier: 0 = explicitly unsupported,
     * 1 = unknown, 2 = explicitly supported.
     */
    public static int compactSupportTier(Platform platform, String extraJson) {
        if (platform != Platform.OPENAI) {
            return 0;
        }
        CompactSupport support = compactSupportKnown(platform, extraJson);
        if (!support.known()) {
            return 1;
        }
        return support.supported() ? 2 : 0;
    }

    public static CompactModelMapping resolveCompactMappedModel(String credentialsJson, String requestedModel) {
        String model = requestedModel == null ? "" : requestedModel.trim();
        if (model.isBlank()) {
            return new CompactModelMapping(model, false);
        }
        Map<String, String> mapping = compactModelMapping(credentialsJson);
        if (mapping.isEmpty()) {
            return new CompactModelMapping(model, false);
        }
        String exact = mapping.get(model);
        if (exact != null) {
            String mapped = exact.trim();
            return new CompactModelMapping(mapped.isBlank() ? model : mapped, true);
        }

        String bestPattern = "";
        String bestValue = "";
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String pattern = entry.getKey();
            if (pattern == null || !pattern.contains("*") || !wildcardMatches(pattern, model)) {
                continue;
            }
            if (pattern.length() > bestPattern.length()) {
                bestPattern = pattern;
                bestValue = entry.getValue();
            }
        }
        if (bestPattern.isBlank()) {
            return new CompactModelMapping(model, false);
        }
        String mapped = bestValue == null ? "" : bestValue.trim();
        return new CompactModelMapping(mapped.isBlank() ? model : mapped, true);
    }

    public static Map<String, String> compactModelMapping(String credentialsJson) {
        JsonNode root = parseObject(credentialsJson);
        if (root == null) {
            return Map.of();
        }
        JsonNode mapping = root.get(CREDENTIAL_COMPACT_MODEL_MAPPING);
        if (mapping == null || !mapping.isObject()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = mapping.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey() == null || field.getKey().isBlank()) {
                continue;
            }
            JsonNode value = field.getValue();
            if (value != null && value.isTextual()) {
                result.put(field.getKey(), value.asText());
            }
        }
        return result;
    }

    private static boolean wildcardMatches(String pattern, String value) {
        String[] parts = pattern.split("\\*", -1);
        int position = 0;
        if (!parts[0].isEmpty()) {
            if (!value.startsWith(parts[0])) {
                return false;
            }
            position = parts[0].length();
        }
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }
            int next = value.indexOf(part, position);
            if (next < 0) {
                return false;
            }
            position = next + part.length();
        }
        String last = parts[parts.length - 1];
        return last.isEmpty() || value.endsWith(last);
    }

    private static JsonNode parseObject(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(json);
            return root != null && root.isObject() ? root : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public record CompactSupport(boolean supported, boolean known) {
    }

    public record CompactModelMapping(String model, boolean matched) {
    }
}
