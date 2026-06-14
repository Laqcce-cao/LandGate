package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.types.gateway.OpenAiFastPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Supplies OpenAI fast-policy settings from Spring configuration.
 *
 * <p>This provider owns configuration binding only. It must not mutate request
 * bodies, build upstream auth, choose routes/accounts, or write responses.</p>
 */
@Slf4j
@Component
class OpenAiFastPolicyProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OpenAiFastPolicy.Settings settings;

    OpenAiFastPolicyProvider(
            @Value("${" + OpenAiFastPolicy.PROPERTY_SETTINGS_JSON + ":}")
            String settingsJson) {
        this.settings = parseSettings(settingsJson);
    }

    OpenAiFastPolicy.Settings current() {
        return settings;
    }

    private static OpenAiFastPolicy.Settings parseSettings(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) {
            return OpenAiFastPolicy.defaultSettings();
        }
        try {
            JsonNode root = JSON.readTree(settingsJson);
            JsonNode rulesNode = root.get(OpenAiFastPolicy.FIELD_RULES);
            if (rulesNode == null || !rulesNode.isArray()) {
                return OpenAiFastPolicy.defaultSettings();
            }
            List<OpenAiFastPolicy.Rule> rules = new ArrayList<>();
            for (JsonNode rule : rulesNode) {
                if (rule == null || !rule.isObject()) {
                    continue;
                }
                rules.add(new OpenAiFastPolicy.Rule(
                        text(rule, OpenAiFastPolicy.FIELD_SERVICE_TIER),
                        text(rule, OpenAiFastPolicy.FIELD_ACTION),
                        text(rule, OpenAiFastPolicy.FIELD_SCOPE),
                        text(rule, OpenAiFastPolicy.FIELD_ERROR_MESSAGE),
                        textArray(rule.get(OpenAiFastPolicy.FIELD_MODEL_WHITELIST)),
                        text(rule, OpenAiFastPolicy.FIELD_FALLBACK_ACTION),
                        text(rule, OpenAiFastPolicy.FIELD_FALLBACK_ERROR_MESSAGE)));
            }
            return new OpenAiFastPolicy.Settings(rules);
        } catch (Exception e) {
            log.warn("Failed to parse OpenAI fast-policy settings; falling back to defaults", e);
            return OpenAiFastPolicy.defaultSettings();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private static List<String> textArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode value : node) {
            if (value != null && value.isTextual() && !value.asText().trim().isBlank()) {
                out.add(value.asText().trim());
            }
        }
        return out;
    }
}
