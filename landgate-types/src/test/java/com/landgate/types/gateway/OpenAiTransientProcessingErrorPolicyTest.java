package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenAI transient processing error policy tests")
class OpenAiTransientProcessingErrorPolicyTest {

    @Test
    @DisplayName("Recognizes Sub2API-compatible OpenAI transient 400 processing messages")
    void recognizesTransientProcessingMessages() {
        assertTrue(OpenAiTransientProcessingErrorPolicy.isTransientProcessingError(
                400,
                "An error occurred while processing your request.",
                null));

        assertTrue(OpenAiTransientProcessingErrorPolicy.isTransientProcessingError(
                400,
                "",
                """
                        {"error":{"message":"You can retry your request, or contact us through our help center at help.openai.com if the error persists. Please include the request ID req_123 in your message."}}
                        """));
    }

    @Test
    @DisplayName("Does not classify normal client validation errors as transient")
    void rejectsValidationErrors() {
        assertFalse(OpenAiTransientProcessingErrorPolicy.isTransientProcessingError(
                400,
                "Missing required parameter: 'instructions'",
                "{\"error\":{\"message\":\"Missing required parameter: 'instructions'\"}}"));

        assertFalse(OpenAiTransientProcessingErrorPolicy.isTransientProcessingError(
                404,
                "An error occurred while processing your request.",
                "{\"error\":{\"message\":\"An error occurred while processing your request.\"}}"));
    }
}
