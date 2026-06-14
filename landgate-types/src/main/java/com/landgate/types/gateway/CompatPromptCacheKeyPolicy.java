package com.landgate.types.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

/**
 * OpenAI Responses compatibility prompt-cache-key derivation policy.
 *
 * <p>Aligned with Sub2API openai_compat_prompt_cache_key behavior.</p>
 */
public final class CompatPromptCacheKeyPolicy {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ANTHROPIC_CACHE_PREFIX = "anthropic-cache-";
    private static final String ANTHROPIC_METADATA_PREFIX = "anthropic-metadata-";
    private static final String ANTHROPIC_DIGEST_PREFIX = "anthropic-digest-";
    private static final String CHAT_COMPLETIONS_COMPAT_PREFIX = "compat_cc_";

    private CompatPromptCacheKeyPolicy() {
    }

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
        String model = textOrDefault(anthropicRequest.get(AnthropicMessagesBodyPolicy.FIELD_MODEL), "");
        if (!shouldAutoInjectPromptCacheKeyForCompat(model)) {
            return "";
        }
        return promptCacheKeyFromAnthropicDigest(buildOpenAICompatAnthropicDigestChain(anthropicRequest));
    }

    public static String deriveChatCompletionsCompatPromptCacheKey(String chatCompletionsBody, String mappedModel) {
        if (chatCompletionsBody == null || chatCompletionsBody.isBlank()) return "";
        try {
            return deriveChatCompletionsCompatPromptCacheKey(JSON.readTree(chatCompletionsBody), mappedModel);
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String deriveChatCompletionsCompatPromptCacheKey(JsonNode chatRequest, String mappedModel) {
        if (chatRequest == null || !chatRequest.isObject()) return "";

        String normalizedModel = normalizeCodexModel(firstNonBlank(
                mappedModel,
                textOrDefault(chatRequest.get(OpenAiChatCompletionsBodyPolicy.FIELD_MODEL), "")));

        List<String> seedParts = new ArrayList<>();
        seedParts.add("model=" + normalizedModel);

        String reasoningEffort = textOrDefault(
                chatRequest.get(OpenAiChatCompletionsBodyPolicy.FIELD_REASONING_EFFORT), "").trim();
        if (!reasoningEffort.isBlank()) {
            seedParts.add("reasoning_effort=" + reasoningEffort);
        }
        JsonNode toolChoice = chatRequest.get(OpenAiChatCompletionsBodyPolicy.FIELD_TOOL_CHOICE);
        if (toolChoice != null && !toolChoice.isNull()) {
            seedParts.add("tool_choice=" + normalizeCompatSeedJson(toolChoice));
        }
        JsonNode tools = chatRequest.get(OpenAiChatCompletionsBodyPolicy.FIELD_TOOLS);
        if (tools != null && tools.isArray() && tools.size() > 0) {
            seedParts.add("tools=" + normalizeCompatSeedJson(tools));
        }
        JsonNode functions = chatRequest.get(OpenAiChatCompletionsBodyPolicy.FIELD_FUNCTIONS);
        if (functions != null && functions.isArray() && functions.size() > 0) {
            seedParts.add("functions=" + normalizeCompatSeedJson(functions));
        }

        boolean firstUserCaptured = false;
        JsonNode messages = chatRequest.get(OpenAiChatCompletionsBodyPolicy.FIELD_MESSAGES);
        if (messages != null && messages.isArray()) {
            for (JsonNode message : messages) {
                String role = textOrDefault(message.get(OpenAiChatCompletionsBodyPolicy.FIELD_ROLE), "").trim();
                JsonNode content = message.get(OpenAiChatCompletionsBodyPolicy.FIELD_CONTENT);
                if (OpenAiChatCompletionsBodyPolicy.ROLE_SYSTEM.equals(role)) {
                    seedParts.add("system=" + normalizeCompatSeedJson(content));
                } else if (OpenAiChatCompletionsBodyPolicy.ROLE_USER.equals(role) && !firstUserCaptured) {
                    seedParts.add("first_user=" + normalizeCompatSeedJson(content));
                    firstUserCaptured = true;
                }
            }
        }

        return CHAT_COMPLETIONS_COMPAT_PREFIX + sha256Hex8(String.join("|", seedParts));
    }

    public static String promptCacheKeyFromAnthropicMetadataSession(JsonNode anthropicRequest) {
        if (anthropicRequest == null || !anthropicRequest.isObject()) return "";
        JsonNode metadata = anthropicRequest.get(AnthropicMessagesBodyPolicy.FIELD_METADATA);
        if (metadata == null || !metadata.isObject()) return "";
        String userId = textOrDefault(metadata.get(AnthropicMessagesBodyPolicy.FIELD_USER_ID), "");
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
        JsonNode system = anthropicRequest.get(AnthropicMessagesBodyPolicy.FIELD_SYSTEM);
        if (system != null && !system.isNull() && !system.toString().isBlank()
                && !"null".equals(system.toString().trim())) {
            parts.add("s:" + sha256Hex16(system.toString()));
        }
        JsonNode messages = anthropicRequest.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGES);
        if (messages != null && messages.isArray()) {
            for (JsonNode message : messages) {
                JsonNode content = message.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT);
                if (content == null || content.isNull() || content.toString().isBlank()) continue;
                String prefix = AnthropicMessagesBodyPolicy.ROLE_ASSISTANT.equals(
                        textOrDefault(message.get(AnthropicMessagesBodyPolicy.FIELD_ROLE), "")) ? "a" : "u";
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
            JsonNode existing = root.get(OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_KEY);
            if (existing != null && existing.isTextual() && !existing.asText().isBlank()) {
                return responsesBody;
            }
            root.put(OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_KEY, promptCacheKey.trim());
            return JSON.writeValueAsString(root);
        } catch (Exception ignored) {
            return responsesBody;
        }
    }

    public static String extractPromptCacheKey(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode value = root.get(OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_KEY);
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

        JsonNode system = anthropicRequest.get(AnthropicMessagesBodyPolicy.FIELD_SYSTEM);
        if (system != null && system.isArray()) {
            for (JsonNode block : system) {
                String text = cacheControlText(block);
                if (!text.isEmpty()) {
                    parts.add("system:" + text);
                }
            }
        }

        String firstUserAnchor = "";
        JsonNode messages = anthropicRequest.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGES);
        if (messages != null && messages.isArray()) {
            for (JsonNode message : messages) {
                String role = textOrDefault(message.get(AnthropicMessagesBodyPolicy.FIELD_ROLE), "");
                JsonNode content = message.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT);
                if (content == null || !content.isArray()) continue;
                for (JsonNode block : content) {
                    String text = cacheControlText(block);
                    if (text.isEmpty()) continue;
                    if (AnthropicMessagesBodyPolicy.ROLE_USER.equals(role) && firstUserAnchor.isEmpty()) {
                        firstUserAnchor = text;
                    } else if (AnthropicMessagesBodyPolicy.ROLE_ASSISTANT.equals(role)) {
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
                || !AnthropicMessagesBodyPolicy.TYPE_TEXT.equals(
                textOrDefault(block.get(AnthropicMessagesBodyPolicy.FIELD_TYPE), ""))
                || isBlankText(block.get(AnthropicMessagesBodyPolicy.FIELD_TEXT))) {
            return "";
        }
        JsonNode cacheControl = block.get(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL);
        if (cacheControl == null || !cacheControl.isObject()
                || !AnthropicMessagesBodyPolicy.CACHE_CONTROL_TYPE_EPHEMERAL.equals(
                textOrDefault(cacheControl.get(AnthropicMessagesBodyPolicy.FIELD_TYPE), ""))) {
            return "";
        }
        return block.get(AnthropicMessagesBodyPolicy.FIELD_TEXT).asText().trim();
    }

    public static boolean shouldAutoInjectPromptCacheKeyForCompat(String model) {
        if (model == null) return false;
        String normalized = model.trim().toLowerCase();
        if (!normalized.contains("gpt-5") && !normalized.contains("codex")) {
            return false;
        }
        String codexModel = normalizeCodexModel(normalized).toLowerCase();
        return codexModel.startsWith("gpt-5") || codexModel.contains("codex");
    }

    private static String normalizeCodexModel(String model) {
        return OpenAiCodexProfile.normalizeModel(model);
    }

    public static String normalizeCompatSeedJson(JsonNode value) {
        if (value == null || value.isMissingNode()) return "";
        try {
            return JSON.writeValueAsString(canonicalize(value));
        } catch (Exception ignored) {
            return value.toString();
        }
    }

    private static JsonNode canonicalize(JsonNode value) {
        if (value == null || value.isNull() || value.isValueNode()) {
            return value;
        }
        if (value.isArray()) {
            ArrayNode out = JSON.createArrayNode();
            for (JsonNode item : value) {
                out.add(canonicalize(item));
            }
            return out;
        }
        if (value.isObject()) {
            ObjectNode out = JSON.createObjectNode();
            TreeSet<String> names = new TreeSet<>();
            Iterator<String> it = value.fieldNames();
            while (it.hasNext()) {
                names.add(it.next());
            }
            for (String name : names) {
                out.set(name, canonicalize(value.get(name)));
            }
            return out;
        }
        return value;
    }

    private static String sha256Hex8(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
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

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String trimmed = trim(value);
            if (!trimmed.isBlank()) return trimmed;
        }
        return "";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
