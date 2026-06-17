package com.landgate.trigger.gateway.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;

import java.util.HashSet;
import java.util.Set;

/**
 * OpenAI Anthropic-compat Responses body normalization.
 *
 * <p>This component owns JSON body mutations required by Sub2API-compatible
 * OpenAI Messages compat session handling. It must not read/write session
 * caches, choose accounts, build auth headers, or execute upstream requests.</p>
 */
final class OpenAiCompatResponsesBodyNormalizer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiCompatResponsesBodyNormalizer() {
    }

    static String ensureInstructionsField(String responsesBody) {
        if (responsesBody == null || responsesBody.isBlank()) return responsesBody;
        try {
            JsonNode parsed = JSON.readTree(responsesBody);
            if (!(parsed instanceof ObjectNode root)) return responsesBody;
            JsonNode instructions = root.get(OpenAiResponsesBodyPolicy.FIELD_INSTRUCTIONS);
            if (instructions != null && instructions.isTextual()) {
                return responsesBody;
            }
            root.put(OpenAiResponsesBodyPolicy.FIELD_INSTRUCTIONS, "");
            return JSON.writeValueAsString(root);
        } catch (Exception ignored) {
            return responsesBody;
        }
    }

    static String removeOpenAiApiKeyUnsupportedResponseFields(String responsesBody) {
        if (responsesBody == null || responsesBody.isBlank()) return responsesBody;
        try {
            JsonNode parsed = JSON.readTree(responsesBody);
            if (!(parsed instanceof ObjectNode root)) return responsesBody;
            boolean changed = false;
            for (String field : OpenAiResponsesBodyPolicy.openAiApiKeyResponsesCompatUnsupportedFields()) {
                if (root.has(field)) {
                    root.remove(field);
                    changed = true;
                }
            }
            return changed ? JSON.writeValueAsString(root) : responsesBody;
        } catch (Exception ignored) {
            return responsesBody;
        }
    }

    static String attachPreviousResponseIdAndTrim(String responsesBody, String previousResponseId) {
        try {
            JsonNode parsed = JSON.readTree(responsesBody);
            if (!(parsed instanceof ObjectNode root)) return responsesBody;
            root.put(OpenAiResponsesBodyPolicy.FIELD_PREVIOUS_RESPONSE_ID, previousResponseId);
            trimInputToLatestTurn(root);
            return JSON.writeValueAsString(root);
        } catch (Exception ignored) {
            return responsesBody;
        }
    }

    private static void trimInputToLatestTurn(ObjectNode root) {
        JsonNode input = root.get(OpenAiResponsesBodyPolicy.FIELD_INPUT);
        if (input == null || !input.isArray() || input.size() == 0) return;
        int start = latestTurnStart((ArrayNode) input);
        if (start <= 0) return;
        ArrayNode trimmed = JSON.createArrayNode();
        for (int i = start; i < input.size(); i++) {
            trimmed.add(input.get(i));
        }
        root.set(OpenAiResponsesBodyPolicy.FIELD_INPUT, trimmed);
    }

    private static int latestTurnStart(ArrayNode items) {
        int start = items.size() - 1;
        JsonNode last = items.get(start);
        String type = last.path(OpenAiResponsesBodyPolicy.FIELD_TYPE).asText("");
        String role = last.path(OpenAiResponsesBodyPolicy.FIELD_ROLE).asText("");
        if (OpenAiResponsesBodyPolicy.TYPE_FUNCTION_CALL_OUTPUT.equals(type)) {
            while (start > 0 && OpenAiResponsesBodyPolicy.TYPE_FUNCTION_CALL_OUTPUT.equals(
                    items.get(start - 1).path(OpenAiResponsesBodyPolicy.FIELD_TYPE).asText(""))) {
                start--;
            }
        } else if (OpenAiResponsesBodyPolicy.TYPE_MESSAGE.equals(type)
                && OpenAiResponsesBodyPolicy.ROLE_USER.equals(role)) {
            while (start > 0 && OpenAiResponsesBodyPolicy.TYPE_FUNCTION_CALL_OUTPUT.equals(
                    items.get(start - 1).path(OpenAiResponsesBodyPolicy.FIELD_TYPE).asText(""))) {
                start--;
            }
        } else {
            return start;
        }
        return expandToolCallStart(items, start);
    }

    private static int expandToolCallStart(ArrayNode items, int start) {
        Set<String> neededCallIds = new HashSet<>();
        for (int i = start; i < items.size(); i++) {
            if (!OpenAiResponsesBodyPolicy.TYPE_FUNCTION_CALL_OUTPUT.equals(
                    items.get(i).path(OpenAiResponsesBodyPolicy.FIELD_TYPE).asText(""))) {
                continue;
            }
            String callId = items.get(i).path(OpenAiResponsesBodyPolicy.FIELD_CALL_ID).asText("").trim();
            if (!callId.isBlank()) neededCallIds.add(callId);
        }
        if (neededCallIds.isEmpty()) return start;

        int expanded = start;
        for (int i = start - 1; i >= 0 && !neededCallIds.isEmpty(); i--) {
            JsonNode item = items.get(i);
            if (!OpenAiResponsesBodyPolicy.TYPE_FUNCTION_CALL.equals(
                    item.path(OpenAiResponsesBodyPolicy.FIELD_TYPE).asText(""))) {
                continue;
            }
            String callId = item.path(OpenAiResponsesBodyPolicy.FIELD_CALL_ID).asText("").trim();
            if (neededCallIds.remove(callId)) {
                expanded = i;
            }
        }
        return expanded;
    }
}
