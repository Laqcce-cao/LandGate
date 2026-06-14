package com.landgate.trigger.gateway.retry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.types.gateway.AnthropicCountTokensPolicy;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.AnthropicThinkingPolicy;

import java.util.Locale;

/**
 * Sub2API-compatible retry policy for Anthropic thinking/signature request errors.
 *
 * <p>This type owns pure error classification and prepared-body filtering. It
 * does not send requests, select accounts, build auth headers, or write
 * servlet responses.</p>
 */
public class AnthropicThinkingRetryPolicy {

    private static final ObjectMapper JSON = new ObjectMapper();

    public boolean shouldRetry(int statusCode, String responseBody) {
        if (statusCode != AnthropicCountTokensPolicy.STATUS_BAD_REQUEST) {
            return false;
        }
        String message = extractErrorMessage(responseBody).toLowerCase(Locale.ROOT).trim();
        if (message.isBlank()) {
            return false;
        }
        if (message.contains("signature")) {
            return true;
        }
        if (message.contains("expected")
                && (message.contains("thinking") || message.contains("redacted_thinking"))) {
            return true;
        }
        if (message.contains("cannot be modified")
                && (message.contains("thinking") || message.contains("redacted_thinking"))) {
            return true;
        }
        return message.contains("non-empty content")
                || message.contains("empty content")
                || message.contains("content blocks must be non-empty");
    }

    public String filterBodyForRetry(String body) {
        return filterBodyForRetryResult(body).body();
    }

    public FilteredBody filterBodyForRetryResult(String body) {
        if (body == null || body.isBlank()) {
            return new FilteredBody(body, false);
        }
        try {
            JsonNode parsed = JSON.readTree(body);
            if (!parsed.isObject()) {
                return new FilteredBody(body, false);
            }
            ObjectNode root = (ObjectNode) parsed;
            boolean modified = false;

            if (root.has(AnthropicThinkingPolicy.FIELD_THINKING)) {
                root.remove(AnthropicThinkingPolicy.FIELD_THINKING);
                removeThinkingDependentContextStrategies(root);
                modified = true;
            }

            JsonNode messages = root.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGES);
            if (messages != null && messages.isArray()) {
                for (JsonNode message : messages) {
                    if (!message.isObject()) {
                        continue;
                    }
                    ObjectNode messageObj = (ObjectNode) message;
                    JsonNode content = messageObj.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT);
                    if (content == null || !content.isArray()) {
                        continue;
                    }
                    FilterResult result = filterContentBlocks((ArrayNode) content);
                    if (result.modified()) {
                        messageObj.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT,
                                result.blocks().isEmpty()
                                        ? placeholderContent(messageObj.path(AnthropicMessagesBodyPolicy.FIELD_ROLE).asText(""))
                                        : result.blocks());
                        modified = true;
                    } else if (content.isEmpty()) {
                        messageObj.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT,
                                placeholderContent(messageObj.path(AnthropicMessagesBodyPolicy.FIELD_ROLE).asText("")));
                        modified = true;
                    }
                }
            }

            return modified
                    ? new FilteredBody(JSON.writeValueAsString(root), true)
                    : new FilteredBody(body, false);
        } catch (Exception e) {
            return new FilteredBody(body, false);
        }
    }

    private static FilterResult filterContentBlocks(ArrayNode content) {
        ArrayNode next = JSON.createArrayNode();
        boolean modified = false;

        for (JsonNode block : content) {
            if (!block.isObject()) {
                next.add(block);
                continue;
            }
            ObjectNode blockObj = (ObjectNode) block;
            String type = blockObj.path(AnthropicMessagesBodyPolicy.FIELD_TYPE).asText("");

            if (AnthropicMessagesBodyPolicy.TYPE_TEXT.equals(type)
                    && blockObj.path(AnthropicMessagesBodyPolicy.FIELD_TEXT).asText("").isEmpty()) {
                modified = true;
                continue;
            }

            if (AnthropicThinkingPolicy.TYPE_THINKING.equals(type)) {
                modified = true;
                String thinking = blockObj.path(AnthropicThinkingPolicy.FIELD_THINKING).asText("");
                if (!thinking.isEmpty()) {
                    ObjectNode text = JSON.createObjectNode();
                    text.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
                    text.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, thinking);
                    next.add(text);
                }
                continue;
            }

            if (AnthropicThinkingPolicy.TYPE_REDACTED_THINKING.equals(type)) {
                modified = true;
                continue;
            }

            if (type.isEmpty() && blockObj.has(AnthropicThinkingPolicy.FIELD_THINKING)) {
                modified = true;
                ObjectNode text = JSON.createObjectNode();
                text.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
                JsonNode thinking = blockObj.get(AnthropicThinkingPolicy.FIELD_THINKING);
                text.put(AnthropicMessagesBodyPolicy.FIELD_TEXT,
                        thinking.isTextual() ? thinking.asText() : thinking.toString());
                next.add(text);
                continue;
            }

            if (AnthropicMessagesBodyPolicy.TYPE_TOOL_RESULT.equals(type)
                    && blockObj.path(AnthropicMessagesBodyPolicy.FIELD_CONTENT).isArray()) {
                FilterResult nested = stripEmptyTextBlocks((ArrayNode) blockObj.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT));
                if (nested.modified()) {
                    ObjectNode copy = blockObj.deepCopy();
                    copy.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, nested.blocks());
                    next.add(copy);
                    modified = true;
                    continue;
                }
            }

            next.add(block);
        }

        return new FilterResult(next, modified);
    }

    private static FilterResult stripEmptyTextBlocks(ArrayNode content) {
        ArrayNode next = JSON.createArrayNode();
        boolean modified = false;
        for (JsonNode block : content) {
            if (block.isObject()
                    && AnthropicMessagesBodyPolicy.TYPE_TEXT.equals(
                    block.path(AnthropicMessagesBodyPolicy.FIELD_TYPE).asText(""))
                    && block.path(AnthropicMessagesBodyPolicy.FIELD_TEXT).asText("").isEmpty()) {
                modified = true;
                continue;
            }
            next.add(block);
        }
        return new FilterResult(next, modified);
    }

    private static ArrayNode placeholderContent(String role) {
        ObjectNode text = JSON.createObjectNode();
        text.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
        text.put(AnthropicMessagesBodyPolicy.FIELD_TEXT,
                AnthropicMessagesBodyPolicy.ROLE_ASSISTANT.equals(role)
                        ? "(assistant content removed)"
                        : "(content removed)");
        ArrayNode content = JSON.createArrayNode();
        content.add(text);
        return content;
    }

    private static void removeThinkingDependentContextStrategies(ObjectNode root) {
        JsonNode edits = root.path(AnthropicThinkingPolicy.FIELD_CONTEXT_MANAGEMENT)
                .path(AnthropicThinkingPolicy.FIELD_EDITS);
        if (!edits.isArray()) {
            return;
        }
        ArrayNode filtered = JSON.createArrayNode();
        boolean removed = false;
        for (JsonNode edit : edits) {
            if (AnthropicThinkingPolicy.CONTEXT_MANAGEMENT_CLEAR_THINKING_EDIT.equals(
                    edit.path(AnthropicMessagesBodyPolicy.FIELD_TYPE).asText(""))) {
                removed = true;
                continue;
            }
            filtered.add(edit);
        }
        if (!removed) {
            return;
        }
        JsonNode contextManagement = root.get(AnthropicThinkingPolicy.FIELD_CONTEXT_MANAGEMENT);
        if (contextManagement != null && contextManagement.isObject()) {
            if (filtered.isEmpty()) {
                ((ObjectNode) contextManagement).remove(AnthropicThinkingPolicy.FIELD_EDITS);
            } else {
                ((ObjectNode) contextManagement).set(AnthropicThinkingPolicy.FIELD_EDITS, filtered);
            }
        }
    }

    private static String extractErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(responseBody);
            String message = root.path("error").path("message").asText("");
            if (!message.isBlank()) {
                return message;
            }
            return root.path("message").asText("");
        } catch (Exception e) {
            return responseBody;
        }
    }

    public record FilteredBody(String body, boolean changed) {
    }

    private record FilterResult(ArrayNode blocks, boolean modified) {
    }
}
