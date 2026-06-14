package com.landgate.trigger.gateway.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.types.gateway.OpenAiAnthropicMessagesCompatPolicy;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;
import lombok.extern.slf4j.Slf4j;

/**
 * Sub2API-compatible Claude Code todo guard insertion for OpenAI Responses
 * bodies produced by Anthropic Messages compatibility routes.
 *
 * <p>This helper mutates/serializes JSON only. It does not decide route
 * eligibility, auth, session state, or protocol translation.</p>
 */
@Slf4j
public final class OpenAiCompatTodoGuard {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiCompatTodoGuard() {
    }

    public static GuardResult appendToResponsesBody(String body) {
        if (body == null || body.isBlank()) {
            return new GuardResult(body, false);
        }
        try {
            JsonNode parsed = JSON.readTree(body);
            if (!(parsed instanceof ObjectNode root)) {
                return new GuardResult(body, false);
            }
            boolean changed = appendToResponsesRoot(root);
            return new GuardResult(changed ? JSON.writeValueAsString(root) : body, changed);
        } catch (Exception e) {
            log.debug("OpenAI compat todo guard skipped invalid body", e);
            return new GuardResult(body, false);
        }
    }

    public static boolean appendToResponsesRoot(ObjectNode root) {
        if (root == null) {
            return false;
        }
        JsonNode input = root.get(OpenAiResponsesBodyPolicy.FIELD_INPUT);
        if (!(input instanceof ArrayNode inputArray) || inputArray.isEmpty()
                || containsMarker(inputArray)) {
            return false;
        }

        ObjectNode guard = JSON.createObjectNode();
        guard.put(OpenAiResponsesBodyPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TYPE_MESSAGE);
        guard.put(OpenAiResponsesBodyPolicy.FIELD_ROLE, OpenAiResponsesBodyPolicy.ROLE_DEVELOPER);

        ArrayNode content = JSON.createArrayNode();
        ObjectNode text = JSON.createObjectNode();
        text.put(OpenAiResponsesBodyPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TYPE_INPUT_TEXT);
        text.put(OpenAiResponsesBodyPolicy.FIELD_TEXT, OpenAiAnthropicMessagesCompatPolicy.TODO_GUARD_TEXT);
        content.add(text);
        guard.set(OpenAiResponsesBodyPolicy.FIELD_CONTENT, content);

        int insertAt = 0;
        while (insertAt < inputArray.size() && isDeveloperMessage(inputArray.get(insertAt))) {
            insertAt++;
        }
        inputArray.insert(insertAt, guard);
        return true;
    }

    private static boolean isDeveloperMessage(JsonNode item) {
        if (item == null || !item.isObject()) {
            return false;
        }
        return OpenAiResponsesBodyPolicy.TYPE_MESSAGE.equals(text(item, OpenAiResponsesBodyPolicy.FIELD_TYPE))
                && OpenAiResponsesBodyPolicy.ROLE_DEVELOPER.equals(text(item, OpenAiResponsesBodyPolicy.FIELD_ROLE));
    }

    private static boolean containsMarker(ArrayNode input) {
        String marker = OpenAiAnthropicMessagesCompatPolicy.TODO_GUARD_MARKER;
        for (JsonNode item : input) {
            if (item != null && item.toString().contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null) return "";
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    public record GuardResult(String body, boolean inserted) {
    }
}
