package com.landgate.types.gateway;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Stable Anthropic thinking/context-management field and value facts.
 *
 * <p>This type owns field names, stable values, and small pure predicates only.
 * It must not parse or mutate request bodies, choose routes, perform auth,
 * translate protocols, or calculate billing.</p>
 */
public final class AnthropicThinkingPolicy {

    public static final String FIELD_BUDGET_TOKENS = "budget_tokens";
    public static final String FIELD_CONTEXT_MANAGEMENT = "context_management";
    public static final String FIELD_DATA = "data";
    public static final String FIELD_EDITS = "edits";
    public static final String FIELD_KEEP = "keep";
    public static final String FIELD_SIGNATURE = "signature";
    public static final String FIELD_THINKING = "thinking";

    public static final String TYPE_THINKING = AnthropicMessagesBodyPolicy.TYPE_THINKING;
    public static final String TYPE_REDACTED_THINKING = AnthropicMessagesBodyPolicy.TYPE_REDACTED_THINKING;
    public static final String TYPE_THINKING_DELTA = "thinking_delta";
    public static final String TYPE_SIGNATURE_DELTA = "signature_delta";

    public static final String THINKING_MODE_ADAPTIVE = "adaptive";
    public static final String THINKING_MODE_DISABLED = "disabled";
    public static final String THINKING_MODE_ENABLED = "enabled";

    public static final String CONTEXT_MANAGEMENT_CLEAR_THINKING_EDIT = "clear_thinking_20251015";
    public static final String CONTEXT_MANAGEMENT_KEEP_ALL = "all";

    private AnthropicThinkingPolicy() {
    }

    public static boolean isThinkingBlockType(String type) {
        return TYPE_THINKING.equals(type) || TYPE_REDACTED_THINKING.equals(type);
    }

    public static boolean shouldInjectContextManagement(JsonNode thinkingNode) {
        if (thinkingNode == null || !thinkingNode.isObject()) return false;
        JsonNode type = thinkingNode.get(AnthropicMessagesBodyPolicy.FIELD_TYPE);
        if (type == null || !type.isTextual()) return false;
        return THINKING_MODE_ENABLED.equals(type.asText())
                || THINKING_MODE_ADAPTIVE.equals(type.asText());
    }
}
