package com.landgate.types.gateway;

import java.util.Set;

/**
 * Sub2API-compatible OpenAI Responses /compact request body shape facts.
 *
 * <p>This policy owns only the compact schema field allowlist. It must not
 * parse or mutate JSON, build requests, choose routes, perform auth, or
 * translate protocols.</p>
 */
public final class OpenAiCompactRequestBodyPolicy {

    private static final Set<String> COMPACT_ALLOWED_FIELDS = Set.of(
            OpenAiResponsesBodyPolicy.FIELD_MODEL,
            OpenAiResponsesBodyPolicy.FIELD_INPUT,
            OpenAiResponsesBodyPolicy.FIELD_INSTRUCTIONS,
            OpenAiResponsesBodyPolicy.FIELD_TOOLS,
            OpenAiResponsesBodyPolicy.FIELD_PARALLEL_TOOL_CALLS,
            OpenAiResponsesBodyPolicy.FIELD_REASONING,
            OpenAiResponsesBodyPolicy.FIELD_TEXT,
            OpenAiResponsesBodyPolicy.FIELD_PREVIOUS_RESPONSE_ID);

    private OpenAiCompactRequestBodyPolicy() {
    }

    public static Set<String> compactAllowedFields() {
        return COMPACT_ALLOWED_FIELDS;
    }

    public static boolean isCompactAllowedField(String field) {
        return COMPACT_ALLOWED_FIELDS.contains(field);
    }
}
