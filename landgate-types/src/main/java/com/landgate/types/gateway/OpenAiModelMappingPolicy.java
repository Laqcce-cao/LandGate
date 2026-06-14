package com.landgate.types.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.types.enums.Platform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sub2API-compatible OpenAI account-level {@code model_mapping} policy.
 *
 * <p>This type owns only credentials.model_mapping extraction and lookup. It
 * does not parse request bodies, mutate JSON, choose routes, build auth headers,
 * or normalize Codex endpoint-specific model aliases.</p>
 */
public final class OpenAiModelMappingPolicy {

    public static final String CREDENTIAL_MODEL_MAPPING = "model_mapping";

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiModelMappingPolicy() {
    }

    public static MappedModel resolveMappedModel(Platform platform,
                                                 String credentialsJson,
                                                 String requestedModel) {
        String model = requestedModel == null ? "" : requestedModel.trim();
        if (platform != Platform.OPENAI || model.isBlank()) {
            return new MappedModel(model, false);
        }

        Map<String, String> mapping = modelMapping(credentialsJson);
        if (mapping.isEmpty()) {
            return new MappedModel(model, false);
        }
        return resolveInMapping(mapping, model);
    }

    public static Map<String, String> modelMapping(String credentialsJson) {
        JsonNode root = parseObject(credentialsJson);
        if (root == null) {
            return Map.of();
        }
        JsonNode mapping = root.get(CREDENTIAL_MODEL_MAPPING);
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
                                                 String credentialsJson,
                                                 String requestedModel) {
        String model = requestedModel == null ? "" : requestedModel.trim();
        if (platform != Platform.OPENAI || model.isBlank()) {
            return false;
        }
        Map<String, String> mapping = modelMapping(credentialsJson);
        if (mapping.isEmpty()) {
            return true;
        }
        return mappingContainsRequestedModel(mapping, model);
    }

    public static boolean hasModelMapping(Platform platform, String credentialsJson) {
        return platform == Platform.OPENAI && !modelMapping(credentialsJson).isEmpty();
    }

    private static MappedModel resolveInMapping(Map<String, String> mapping, String requestedModel) {
        if (mapping.containsKey(requestedModel)) {
            return new MappedModel(mapping.get(requestedModel), true);
        }

        List<Map.Entry<String, String>> matches = new ArrayList<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            if (wildcardMatches(entry.getKey(), requestedModel)) {
                matches.add(entry);
            }
        }
        if (matches.isEmpty()) {
            return new MappedModel(requestedModel, false);
        }
        matches.sort(Comparator
                .<Map.Entry<String, String>>comparingInt(entry -> entry.getKey().length())
                .reversed()
                .thenComparing(Map.Entry::getKey));
        return new MappedModel(matches.get(0).getValue(), true);
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

    private static boolean wildcardMatches(String pattern, String value) {
        if (pattern == null || value == null || !pattern.endsWith("*")) {
            return false;
        }
        String prefix = pattern.substring(0, pattern.length() - 1);
        return value.startsWith(prefix);
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
