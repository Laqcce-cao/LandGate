package com.landgate.types.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sub2API-compatible OpenAI content-derived session seed policy.
 *
 * <p>This policy returns the raw {@code compat_cs_...} seed material. Callers
 * are responsible for hashing and API-key/user isolation.</p>
 */
public final class OpenAiContentSessionSeedPolicy {

    public static final String PREFIX = "compat_cs_";

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiContentSessionSeedPolicy() {
    }

    public static String derive(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            return derive(JSON.readTree(body));
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String derive(JsonNode root) {
        if (root == null || !root.isObject()) return "";
        StringBuilder seed = new StringBuilder();

        String model = text(root.get(OpenAiResponsesBodyPolicy.FIELD_MODEL));
        if (!model.isBlank()) {
            seed.append("model=").append(model);
        }

        JsonNode tools = root.get(OpenAiResponsesBodyPolicy.FIELD_TOOLS);
        if (tools != null && tools.isArray() && tools.size() > 0) {
            seed.append("|tools=").append(CompatPromptCacheKeyPolicy.normalizeCompatSeedJson(tools));
        }

        JsonNode functions = root.get(OpenAiChatCompletionsBodyPolicy.FIELD_FUNCTIONS);
        if (functions != null && functions.isArray() && functions.size() > 0) {
            seed.append("|functions=").append(CompatPromptCacheKeyPolicy.normalizeCompatSeedJson(functions));
        }

        String instructions = text(root.get(OpenAiResponsesBodyPolicy.FIELD_INSTRUCTIONS));
        if (!instructions.isBlank()) {
            seed.append("|instructions=").append(instructions);
        }

        JsonNode messages = root.get(OpenAiChatCompletionsBodyPolicy.FIELD_MESSAGES);
        if (messages != null && messages.isArray()) {
            appendMessageSeed(seed, messages);
        } else {
            appendResponsesInputSeed(seed, root.get(OpenAiResponsesBodyPolicy.FIELD_INPUT));
        }

        return seed.isEmpty() ? "" : PREFIX + seed;
    }

    private static void appendMessageSeed(StringBuilder seed, JsonNode messages) {
        boolean firstUserCaptured = false;
        for (JsonNode message : messages) {
            String role = text(message.get(OpenAiChatCompletionsBodyPolicy.FIELD_ROLE));
            JsonNode content = message.get(OpenAiChatCompletionsBodyPolicy.FIELD_CONTENT);
            if (OpenAiChatCompletionsBodyPolicy.ROLE_SYSTEM.equals(role)
                    || OpenAiChatCompletionsBodyPolicy.ROLE_DEVELOPER.equals(role)) {
                seed.append("|system=").append(CompatPromptCacheKeyPolicy.normalizeCompatSeedJson(content));
            } else if (OpenAiChatCompletionsBodyPolicy.ROLE_USER.equals(role) && !firstUserCaptured) {
                seed.append("|first_user=").append(CompatPromptCacheKeyPolicy.normalizeCompatSeedJson(content));
                firstUserCaptured = true;
            }
        }
    }

    private static void appendResponsesInputSeed(StringBuilder seed, JsonNode input) {
        if (input == null || input.isNull()) return;
        if (input.isTextual() && !input.asText().isBlank()) {
            seed.append("|input=").append(input.asText());
            return;
        }
        if (!input.isArray()) return;

        boolean firstUserCaptured = false;
        for (JsonNode item : input) {
            String role = text(item.get(OpenAiResponsesBodyPolicy.FIELD_ROLE));
            JsonNode content = item.get(OpenAiResponsesBodyPolicy.FIELD_CONTENT);
            if (OpenAiResponsesBodyPolicy.ROLE_DEVELOPER.equals(role)
                    || OpenAiChatCompletionsBodyPolicy.ROLE_SYSTEM.equals(role)) {
                seed.append("|system=").append(CompatPromptCacheKeyPolicy.normalizeCompatSeedJson(content));
            } else if (OpenAiResponsesBodyPolicy.ROLE_USER.equals(role) && !firstUserCaptured) {
                seed.append("|first_user=").append(CompatPromptCacheKeyPolicy.normalizeCompatSeedJson(content));
                firstUserCaptured = true;
            }

            if (!firstUserCaptured
                    && OpenAiResponsesBodyPolicy.TYPE_INPUT_TEXT.equals(text(item.get(OpenAiResponsesBodyPolicy.FIELD_TYPE)))) {
                String inputText = text(item.get(OpenAiResponsesBodyPolicy.FIELD_TEXT));
                if (!inputText.isBlank()) {
                    seed.append("|first_user=").append(inputText);
                    firstUserCaptured = true;
                }
            }
        }
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText().trim() : "";
    }
}
