package com.landgate.types.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sub2API-compatible Anthropic account-level model_mapping policy.
 *
 * <p>This type owns only model_mapping extraction and lookup. It must not parse
 * request bodies, mutate JSON, build HTTP requests, choose accounts, or perform
 * auth.</p>
 */
public final class AnthropicModelMappingPolicy {

    public static final String EXTRA_MODEL_MAPPING = "model_mapping";

    private static final ObjectMapper JSON = new ObjectMapper();

    private AnthropicModelMappingPolicy() {
    }

    public static MappedModel resolveMappedModel(Platform platform,
                                                 AccountType accountType,
                                                 String extraJson,
                                                 String requestedModel) {
        String model = requestedModel == null ? "" : requestedModel.trim();
        if (platform != Platform.ANTHROPIC || accountType != AccountType.API_KEY || model.isBlank()) {
            return new MappedModel(model, false);
        }

        Map<String, String> mapping = modelMapping(extraJson);
        if (mapping.isEmpty()) {
            return new MappedModel(model, false);
        }

        if (mapping.containsKey(model)) {
            return new MappedModel(mapping.get(model), true);
        }

        String bestPattern = "";
        String bestValue = "";
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String pattern = entry.getKey();
            if (pattern == null || !wildcardMatches(pattern, model)) {
                continue;
            }
            if (pattern.length() > bestPattern.length()) {
                bestPattern = pattern;
                bestValue = entry.getValue();
            }
        }
        if (bestPattern.isBlank()) {
            return new MappedModel(model, false);
        }
        return new MappedModel(bestValue, true);
    }

    public static Map<String, String> modelMapping(String extraJson) {
        JsonNode root = parseObject(extraJson);
        if (root == null) {
            return Map.of();
        }
        JsonNode mapping = root.get(EXTRA_MODEL_MAPPING);
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

    public static boolean supportsRequestedModel(Platform platform,
                                                 AccountType accountType,
                                                 String extraJson,
                                                 String requestedModel) {
        String model = requestedModel == null ? "" : requestedModel.trim();
        if (platform != Platform.ANTHROPIC || accountType != AccountType.API_KEY || model.isBlank()) {
            return false;
        }
        Map<String, String> mapping = modelMapping(extraJson);
        if (mapping.isEmpty()) {
            return true;
        }
        return mappingContainsRequestedModel(mapping, model);
    }

    public static boolean hasModelMapping(Platform platform, AccountType accountType, String extraJson) {
        return platform == Platform.ANTHROPIC
                && accountType == AccountType.API_KEY
                && !modelMapping(extraJson).isEmpty();
    }

    private static boolean wildcardMatches(String pattern, String value) {
        if (pattern == null || value == null || !pattern.endsWith("*")) {
            return false;
        }
        String prefix = pattern.substring(0, pattern.length() - 1);
        return value.startsWith(prefix);
    }

    private static boolean mappingContainsRequestedModel(Map<String, String> mapping, String requestedModel) {
        if (mapping.containsKey(requestedModel)) {
            return true;
        }
        for (String pattern : mapping.keySet()) {
            if (wildcardMatches(pattern, requestedModel)) {
                return true;
            }
        }
        return false;
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

    public record MappedModel(String model, boolean matched) {
    }
}
