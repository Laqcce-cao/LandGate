package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;

/**
 * Sub2API-compatible Anthropic request pre-filter for empty text blocks.
 */
final class AnthropicEmptyTextBlockNormalizer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AnthropicEmptyTextBlockNormalizer() {
    }

    static String normalize(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        try {
            JsonNode parsed = JSON.readTree(body);
            if (!(parsed instanceof ObjectNode root)) {
                return body;
            }
            JsonNode messages = root.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGES);
            if (messages == null || !messages.isArray()) {
                return body;
            }

            boolean changed = false;
            for (JsonNode message : messages) {
                if (!(message instanceof ObjectNode messageObject)) {
                    continue;
                }
                JsonNode content = messageObject.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT);
                if (content == null || !content.isArray()) {
                    continue;
                }
                FilterResult filtered = filterContent((ArrayNode) content);
                if (filtered.changed()) {
                    messageObject.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, filtered.blocks());
                    changed = true;
                }
            }

            return changed ? JSON.writeValueAsString(root) : body;
        } catch (Exception ignored) {
            return body;
        }
    }

    private static FilterResult filterContent(ArrayNode content) {
        ArrayNode next = JSON.createArrayNode();
        boolean changed = false;

        for (JsonNode block : content) {
            if (!(block instanceof ObjectNode blockObject)) {
                next.add(block);
                continue;
            }

            String type = blockObject.path(AnthropicMessagesBodyPolicy.FIELD_TYPE).asText("");
            if (AnthropicMessagesBodyPolicy.TYPE_TEXT.equals(type)
                    && blockObject.path(AnthropicMessagesBodyPolicy.FIELD_TEXT).asText("").isEmpty()) {
                changed = true;
                continue;
            }

            if (AnthropicMessagesBodyPolicy.TYPE_TOOL_RESULT.equals(type)
                    && blockObject.path(AnthropicMessagesBodyPolicy.FIELD_CONTENT).isArray()) {
                FilterResult nested = filterContent((ArrayNode) blockObject.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT));
                if (nested.changed()) {
                    ObjectNode copy = blockObject.deepCopy();
                    copy.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, nested.blocks());
                    next.add(copy);
                    changed = true;
                    continue;
                }
            }

            next.add(block);
        }

        return new FilterResult(next, changed);
    }

    private record FilterResult(ArrayNode blocks, boolean changed) {
    }
}
