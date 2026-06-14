package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenAI compact request body policy tests")
class OpenAiCompactRequestBodyPolicyTest {

    @Test
    @DisplayName("Sub2API-compatible /compact body field allowlist is centralized")
    void compactAllowedFieldsAreCentralized() {
        assertTrue(OpenAiCompactRequestBodyPolicy.isCompactAllowedField(OpenAiResponsesBodyPolicy.FIELD_MODEL));
        assertTrue(OpenAiCompactRequestBodyPolicy.isCompactAllowedField(OpenAiResponsesBodyPolicy.FIELD_INPUT));
        assertTrue(OpenAiCompactRequestBodyPolicy.isCompactAllowedField(OpenAiResponsesBodyPolicy.FIELD_INSTRUCTIONS));
        assertTrue(OpenAiCompactRequestBodyPolicy.isCompactAllowedField(OpenAiResponsesBodyPolicy.FIELD_TOOLS));
        assertTrue(OpenAiCompactRequestBodyPolicy.isCompactAllowedField(OpenAiResponsesBodyPolicy.FIELD_PARALLEL_TOOL_CALLS));
        assertTrue(OpenAiCompactRequestBodyPolicy.isCompactAllowedField(OpenAiResponsesBodyPolicy.FIELD_REASONING));
        assertTrue(OpenAiCompactRequestBodyPolicy.isCompactAllowedField(OpenAiResponsesBodyPolicy.FIELD_TEXT));
        assertTrue(OpenAiCompactRequestBodyPolicy.isCompactAllowedField(OpenAiResponsesBodyPolicy.FIELD_PREVIOUS_RESPONSE_ID));

        assertFalse(OpenAiCompactRequestBodyPolicy.isCompactAllowedField(OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_KEY));
        assertFalse(OpenAiCompactRequestBodyPolicy.isCompactAllowedField(OpenAiResponsesBodyPolicy.FIELD_STORE));
        assertFalse(OpenAiCompactRequestBodyPolicy.isCompactAllowedField(OpenAiResponsesBodyPolicy.FIELD_STREAM));
        assertFalse(OpenAiCompactRequestBodyPolicy.isCompactAllowedField(OpenAiResponsesBodyPolicy.FIELD_SERVICE_TIER));
    }
}
