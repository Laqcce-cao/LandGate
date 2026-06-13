package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Normalizes public OpenAI Responses API-key requests before forwarding upstream.
 */
@Slf4j
@Component
public class OpenAiResponsesRequestNormalizer {

    private static final ObjectMapper JSON = new ObjectMapper();

    public String normalize(String body) {
        try {
            JsonNode parsed = JSON.readTree(body);
            if (!(parsed instanceof ObjectNode root)) return body;
            normalizeOpenAIServiceTier(root);
            root.remove("max_completion_tokens");
            root.remove("prompt_cache_retention");
            root.remove("safety_identifier");
            sanitizeEmptyBase64InputImages(root);
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Failed to normalize OpenAI API key Responses request body", e);
            return body;
        }
    }

    private static void normalizeOpenAIServiceTier(ObjectNode root) {
        JsonNode value = root.get("service_tier");
        if (value == null || !value.isTextual()) {
            return;
        }
        String normalized = normalizedOpenAIServiceTierValue(value.asText());
        if (normalized.isBlank()) {
            root.remove("service_tier");
        } else {
            root.put("service_tier", normalized);
        }
    }

    private static String normalizedOpenAIServiceTierValue(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase();
        if (value.isBlank()) return "";
        if ("fast".equals(value)) {
            value = "priority";
        }
        return switch (value) {
            case "priority", "flex", "auto", "default", "scale" -> value;
            default -> "";
        };
    }

    private static void sanitizeEmptyBase64InputImages(ObjectNode root) {
        JsonNode input = root.get("input");
        if (input == null || !input.isArray()) {
            return;
        }
        var normalizedItems = JSON.createArrayNode();
        boolean changed = false;
        for (JsonNode item : input) {
            if (!(item instanceof ObjectNode itemObject)) {
                normalizedItems.add(item);
                continue;
            }
            if (shouldDropEmptyBase64InputImagePart(itemObject)) {
                changed = true;
                continue;
            }
            JsonNode content = itemObject.get("content");
            if (content == null || !content.isArray()) {
                normalizedItems.add(item);
                continue;
            }
            var normalizedParts = JSON.createArrayNode();
            boolean itemChanged = false;
            for (JsonNode part : content) {
                if (part instanceof ObjectNode partObject && shouldDropEmptyBase64InputImagePart(partObject)) {
                    changed = true;
                    itemChanged = true;
                    continue;
                }
                normalizedParts.add(part);
            }
            if (itemChanged) {
                if (normalizedParts.isEmpty()) {
                    continue;
                }
                ObjectNode copied = itemObject.deepCopy();
                copied.set("content", normalizedParts);
                normalizedItems.add(copied);
            } else {
                normalizedItems.add(item);
            }
        }
        if (changed) {
            root.set("input", normalizedItems);
        }
    }

    private static boolean shouldDropEmptyBase64InputImagePart(ObjectNode part) {
        JsonNode type = part.get("type");
        JsonNode imageUrl = part.get("image_url");
        return type != null && type.isTextual() && "input_image".equals(type.asText().trim())
                && imageUrl != null && imageUrl.isTextual()
                && isEmptyBase64DataURI(imageUrl.asText().trim());
    }

    private static boolean isEmptyBase64DataURI(String raw) {
        if (raw == null || !raw.startsWith("data:")) return false;
        String rest = raw.substring("data:".length());
        int semicolon = rest.indexOf(';');
        if (semicolon < 0) return false;
        rest = rest.substring(semicolon + 1);
        if (!rest.startsWith("base64,")) return false;
        return rest.substring("base64,".length()).trim().isEmpty();
    }
}
