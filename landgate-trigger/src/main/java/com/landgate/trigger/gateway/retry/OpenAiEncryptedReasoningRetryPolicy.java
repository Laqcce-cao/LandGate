package com.landgate.trigger.gateway.retry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;
import com.landgate.types.gateway.OpenAiResponsesJsonPolicy;
import lombok.extern.slf4j.Slf4j;

/**
 * Sub2API-compatible retry policy for OpenAI {@code invalid_encrypted_content}
 * HTTP responses.
 *
 * <p>This class owns only error classification and prepared upstream body
 * sanitization. The handler owns retry flow, account selection, auth, and
 * response writing.</p>
 */
@Slf4j
public class OpenAiEncryptedReasoningRetryPolicy {

    private static final ObjectMapper JSON = new ObjectMapper();

    public boolean shouldRetry(int statusCode, String errorBody) {
        if (statusCode != 400 || errorBody == null || errorBody.isBlank()) {
            return false;
        }
        try {
            JsonNode root = JSON.readTree(errorBody);
            JsonNode error = root.get(OpenAiResponsesJsonPolicy.FIELD_ERROR);
            JsonNode code = error != null && error.isObject()
                    ? error.get(OpenAiResponsesJsonPolicy.FIELD_CODE)
                    : root.get(OpenAiResponsesJsonPolicy.FIELD_CODE);
            return code != null
                    && code.isTextual()
                    && OpenAiResponsesJsonPolicy.ERROR_CODE_INVALID_ENCRYPTED_CONTENT.equals(code.asText().trim());
        } catch (Exception ignored) {
            return false;
        }
    }

    public SanitizedBody sanitizePreparedBody(String body) {
        if (body == null || body.isBlank()) {
            return new SanitizedBody(body, false);
        }
        try {
            JsonNode parsed = JSON.readTree(body);
            if (!(parsed instanceof ObjectNode root)) {
                return new SanitizedBody(body, false);
            }
            boolean changed = sanitizeInput(root);
            return changed
                    ? new SanitizedBody(JSON.writeValueAsString(root), true)
                    : new SanitizedBody(body, false);
        } catch (Exception e) {
            log.debug("Failed to sanitize OpenAI encrypted reasoning retry body", e);
            return new SanitizedBody(body, false);
        }
    }

    private static boolean sanitizeInput(ObjectNode root) {
        JsonNode input = root.get(OpenAiResponsesBodyPolicy.FIELD_INPUT);
        if (input instanceof ArrayNode inputArray) {
            ArrayNode filtered = JSON.createArrayNode();
            boolean changed = false;
            for (JsonNode item : inputArray) {
                SanitizedItem sanitized = sanitizeInputItem(item);
                changed = changed || sanitized.changed();
                if (sanitized.keep()) {
                    filtered.add(sanitized.item());
                }
            }
            if (!changed) {
                return false;
            }
            if (filtered.isEmpty()) {
                root.remove(OpenAiResponsesBodyPolicy.FIELD_INPUT);
            } else {
                root.set(OpenAiResponsesBodyPolicy.FIELD_INPUT, filtered);
            }
            return true;
        }
        if (input instanceof ObjectNode inputObject) {
            SanitizedItem sanitized = sanitizeInputItem(inputObject);
            if (!sanitized.changed()) {
                return false;
            }
            if (sanitized.keep()) {
                root.set(OpenAiResponsesBodyPolicy.FIELD_INPUT, sanitized.item());
            } else {
                root.remove(OpenAiResponsesBodyPolicy.FIELD_INPUT);
            }
            return true;
        }
        return false;
    }

    private static SanitizedItem sanitizeInputItem(JsonNode item) {
        if (!(item instanceof ObjectNode object)) {
            return new SanitizedItem(item, false, true);
        }
        JsonNode type = object.get(OpenAiResponsesJsonPolicy.FIELD_TYPE);
        if (type == null
                || !type.isTextual()
                || !OpenAiResponsesJsonPolicy.TYPE_REASONING.equals(type.asText().trim())
                || !object.has(OpenAiResponsesJsonPolicy.FIELD_ENCRYPTED_CONTENT)) {
            return new SanitizedItem(item, false, true);
        }
        ObjectNode copy = object.deepCopy();
        copy.remove(OpenAiResponsesJsonPolicy.FIELD_ENCRYPTED_CONTENT);
        return new SanitizedItem(copy, true, copy.size() > 1);
    }

    public record SanitizedBody(String body, boolean changed) {
    }

    private record SanitizedItem(JsonNode item, boolean changed, boolean keep) {
    }
}
