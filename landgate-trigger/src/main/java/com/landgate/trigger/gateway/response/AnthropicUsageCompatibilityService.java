package com.landgate.trigger.gateway.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.AnthropicMessagesSsePolicy;
import org.springframework.stereotype.Service;

@Service
public class AnthropicUsageCompatibilityService {

    private static final ObjectMapper JSON = new ObjectMapper();

    public String normalizeNonStreamingBody(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isObject()) {
                return body;
            }
            ObjectNode usage = objectChild((ObjectNode) root, AnthropicMessagesBodyPolicy.FIELD_USAGE);
            boolean changed = normalizeUsageObject(usage);
            return changed ? JSON.writeValueAsString(root) : body;
        } catch (Exception ignored) {
            return body;
        }
    }

    public String normalizeSseLine(String line) {
        String payload = AnthropicMessagesSsePolicy.extractDataPayload(line);
        if (payload == null || AnthropicMessagesSsePolicy.isDoneSentinel(payload)) {
            return line;
        }
        try {
            JsonNode root = JSON.readTree(payload);
            if (!root.isObject()) {
                return line;
            }
            boolean changed = switch (root.path(AnthropicMessagesBodyPolicy.FIELD_TYPE).asText("")) {
                case AnthropicMessagesSsePolicy.EVENT_MESSAGE_START -> {
                    ObjectNode message = objectChild((ObjectNode) root, AnthropicMessagesBodyPolicy.FIELD_MESSAGE);
                    yield normalizeUsageObject(objectChild(message, AnthropicMessagesBodyPolicy.FIELD_USAGE));
                }
                case AnthropicMessagesSsePolicy.EVENT_MESSAGE_DELTA ->
                        normalizeUsageObject(objectChild((ObjectNode) root, AnthropicMessagesBodyPolicy.FIELD_USAGE));
                default -> false;
            };
            return changed
                    ? AnthropicMessagesSsePolicy.DATA_LINE_PREFIX + JSON.writeValueAsString(root)
                    : line;
        } catch (Exception ignored) {
            return line;
        }
    }

    private static boolean normalizeUsageObject(ObjectNode usage) {
        boolean changed = false;
        int cacheRead = usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_READ_INPUT_TOKENS).asInt();
        int cached = usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHED_TOKENS).asInt();
        if (cacheRead == 0 && cached > 0) {
            usage.put(AnthropicMessagesBodyPolicy.FIELD_CACHE_READ_INPUT_TOKENS, cached);
            changed = true;
        }

        int cacheCreation = usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_CREATION_INPUT_TOKENS).asInt();
        if (cacheCreation == 0) {
            JsonNode cacheCreationNode = usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_CREATION);
            int cacheCreation5m = cacheCreationNode.path(AnthropicMessagesBodyPolicy.FIELD_EPHEMERAL_5M_INPUT_TOKENS)
                    .asInt();
            int cacheCreation1h = cacheCreationNode.path(AnthropicMessagesBodyPolicy.FIELD_EPHEMERAL_1H_INPUT_TOKENS)
                    .asInt();
            int total = cacheCreation5m + cacheCreation1h;
            if (total > 0) {
                usage.put(AnthropicMessagesBodyPolicy.FIELD_CACHE_CREATION_INPUT_TOKENS, total);
                changed = true;
            }
        }
        return changed;
    }

    private static ObjectNode objectChild(ObjectNode parent, String field) {
        JsonNode current = parent.path(field);
        if (current.isObject()) {
            return (ObjectNode) current;
        }
        ObjectNode child = JSON.createObjectNode();
        parent.set(field, child);
        return child;
    }
}
