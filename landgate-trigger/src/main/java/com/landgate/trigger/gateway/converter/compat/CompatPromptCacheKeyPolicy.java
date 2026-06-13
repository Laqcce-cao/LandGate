package com.landgate.trigger.gateway.converter.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.trigger.gateway.oauth.MetadataUserIdParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Responses 兼容缓存键派生策略。
 * <p>
 * 对齐 sub2api {@code openai_compat_prompt_cache_key.go}：
 * <ol>
 *   <li>metadata.user_id.session_id 派生 {@code anthropic-metadata-*}</li>
 *   <li>Anthropic cache_control 锚点派生 {@code anthropic-cache-*}</li>
 *   <li>无锚点时，从 Anthropic replay digest 派生 {@code anthropic-digest-*}</li>
 * </ol>
 */
public final class CompatPromptCacheKeyPolicy {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ANTHROPIC_CACHE_PREFIX = "anthropic-cache-";
    private static final String ANTHROPIC_METADATA_PREFIX = "anthropic-metadata-";
    private static final String ANTHROPIC_DIGEST_PREFIX = "anthropic-digest-";

    private CompatPromptCacheKeyPolicy() {}

    public static String deriveAnthropicCompatPromptCacheKey(JsonNode anthropicRequest) {
        if (anthropicRequest == null || !anthropicRequest.isObject()) return "";
        String metadataKey = promptCacheKeyFromAnthropicMetadataSession(anthropicRequest);
        if (!metadataKey.isEmpty()) {
            return metadataKey;
        }
        String anchorKey = deriveAnthropicCacheControlPromptCacheKey(anthropicRequest);
        if (!anchorKey.isEmpty()) {
            return anchorKey;
        }
        String model = textOrDefault(anthropicRequest.get("model"), "");
        if (!shouldAutoInjectPromptCacheKeyForCompat(model)) {
            return "";
        }
        return promptCacheKeyFromAnthropicDigest(buildOpenAICompatAnthropicDigestChain(anthropicRequest));
    }

    public static String promptCacheKeyFromAnthropicMetadataSession(JsonNode anthropicRequest) {
        if (anthropicRequest == null || !anthropicRequest.isObject()) return "";
        JsonNode metadata = anthropicRequest.get("metadata");
        if (metadata == null || !metadata.isObject()) return "";
        String userId = textOrDefault(metadata.get("user_id"), "");
        MetadataUserIdParser.ParsedMetadataUserId parsed = MetadataUserIdParser.parse(userId);
        if (parsed == null || parsed.sessionId() == null || parsed.sessionId().isBlank()) {
            return "";
        }
        String seed = String.join("|",
                "anthropic-metadata",
                trim(parsed.deviceId()),
                trim(parsed.accountUuid()),
                trim(parsed.sessionId()));
        return ANTHROPIC_METADATA_PREFIX + sha256Hex16(seed);
    }

    public static String buildOpenAICompatAnthropicDigestChain(JsonNode anthropicRequest) {
        if (anthropicRequest == null || !anthropicRequest.isObject()) return "";
        List<String> parts = new ArrayList<>();
        JsonNode system = anthropicRequest.get("system");
        if (system != null && !system.isNull() && !system.toString().isBlank()
                && !"null".equals(system.toString().trim())) {
            parts.add("s:" + sha256Hex16(system.toString()));
        }
        JsonNode messages = anthropicRequest.get("messages");
        if (messages != null && messages.isArray()) {
            for (JsonNode message : messages) {
                JsonNode content = message.get("content");
                if (content == null || content.isNull() || content.toString().isBlank()) continue;
                String prefix = "assistant".equals(textOrDefault(message.get("role"), "")) ? "a" : "u";
                parts.add(prefix + ":" + sha256Hex16(content.toString()));
            }
        }
        return String.join("-", parts);
    }

    public static String promptCacheKeyFromAnthropicDigest(String digestChain) {
        if (digestChain == null || digestChain.isBlank()) return "";
        return ANTHROPIC_DIGEST_PREFIX + sha256Hex16(digestChain);
    }

    public static String deriveAnthropicCacheControlPromptCacheKey(JsonNode anthropicRequest) {
        List<String> parts = collectAnthropicCacheControlAnchorParts(anthropicRequest);
        if (parts.isEmpty()) {
            return "";
        }
        return ANTHROPIC_CACHE_PREFIX + sha256Hex16("anthropic-cache:" + String.join("\n", parts));
    }

    public static String deriveAnthropicCacheControlSessionAnchor(JsonNode anthropicRequest) {
        List<String> parts = collectAnthropicCacheControlAnchorParts(anthropicRequest);
        return parts.isEmpty() ? "" : String.join("\n", parts);
    }

    public static String injectPromptCacheKey(String responsesBody, String promptCacheKey) {
        if (responsesBody == null || responsesBody.isBlank()
                || promptCacheKey == null || promptCacheKey.isBlank()) {
            return responsesBody;
        }
        try {
            JsonNode parsed = JSON.readTree(responsesBody);
            if (!(parsed instanceof ObjectNode root)) {
                return responsesBody;
            }
            JsonNode existing = root.get("prompt_cache_key");
            if (existing != null && existing.isTextual() && !existing.asText().isBlank()) {
                return responsesBody;
            }
            root.put("prompt_cache_key", promptCacheKey.trim());
            return JSON.writeValueAsString(root);
        } catch (Exception ignored) {
            return responsesBody;
        }
    }

    public static String extractPromptCacheKey(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode value = root.get("prompt_cache_key");
            return value != null && value.isTextual() ? value.asText().trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String extractAnthropicCacheControlSessionAnchor(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            return deriveAnthropicCacheControlSessionAnchor(JSON.readTree(body));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<String> collectAnthropicCacheControlAnchorParts(JsonNode anthropicRequest) {
        List<String> parts = new ArrayList<>();
        if (anthropicRequest == null || !anthropicRequest.isObject()) return parts;

        JsonNode system = anthropicRequest.get("system");
        if (system != null && system.isArray()) {
            for (JsonNode block : system) {
                String text = cacheControlText(block);
                if (!text.isEmpty()) {
                    parts.add("system:" + text);
                }
            }
        }

        String firstUserAnchor = "";
        JsonNode messages = anthropicRequest.get("messages");
        if (messages != null && messages.isArray()) {
            for (JsonNode message : messages) {
                String role = textOrDefault(message.get("role"), "");
                JsonNode content = message.get("content");
                if (content == null || !content.isArray()) continue;
                for (JsonNode block : content) {
                    String text = cacheControlText(block);
                    if (text.isEmpty()) continue;
                    if ("user".equals(role) && firstUserAnchor.isEmpty()) {
                        firstUserAnchor = text;
                    } else if ("assistant".equals(role)) {
                        parts.add("assistant:" + text);
                    }
                }
            }
        }
        if (!firstUserAnchor.isEmpty()) {
            parts.add("user_anchor:" + firstUserAnchor);
        }
        return parts;
    }

    private static String cacheControlText(JsonNode block) {
        if (block == null || !block.isObject()
                || !"text".equals(textOrDefault(block.get("type"), ""))
                || isBlankText(block.get("text"))) {
            return "";
        }
        JsonNode cacheControl = block.get("cache_control");
        if (cacheControl == null || !cacheControl.isObject()
                || !"ephemeral".equals(textOrDefault(cacheControl.get("type"), ""))) {
            return "";
        }
        return block.get("text").asText().trim();
    }

    public static boolean shouldAutoInjectPromptCacheKeyForCompat(String model) {
        if (model == null) return false;
        String normalized = model.trim().toLowerCase();
        return normalized.contains("gpt-5") || normalized.contains("codex");
    }

    private static String sha256Hex16(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static boolean isBlankText(JsonNode node) {
        return node == null || !node.isTextual() || node.asText().isBlank();
    }

    private static String textOrDefault(JsonNode node, String defaultValue) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : defaultValue;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
