package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAI Responses HTTP request policy tests")
class OpenAiResponsesHttpRequestPolicyTest {

    @Test
    @DisplayName("Sub2API-compatible HTTP Responses validation messages are centralized")
    void validationMessagesAreCentralized() {
        assertEquals(400, OpenAiResponsesHttpRequestPolicy.STATUS_BAD_REQUEST);
        assertEquals("invalid_request_error", OpenAiResponsesHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST);
        assertEquals("Request body is empty", OpenAiResponsesHttpRequestPolicy.MESSAGE_EMPTY_BODY);
        assertEquals("Failed to parse request body", OpenAiResponsesHttpRequestPolicy.MESSAGE_PARSE_BODY_FAILED);
        assertEquals("model is required", OpenAiResponsesHttpRequestPolicy.MESSAGE_MODEL_REQUIRED);
        assertEquals("invalid stream field type", OpenAiResponsesHttpRequestPolicy.MESSAGE_INVALID_STREAM_TYPE);
        assertEquals("function_call_output requires call_id on HTTP requests; continuation via previous_response_id is only supported on Responses WebSocket v2",
                OpenAiResponsesHttpRequestPolicy.MESSAGE_FUNCTION_CALL_OUTPUT_REQUIRES_CALL_ID);
        assertEquals("function_call_output requires item_reference ids matching each call_id on HTTP requests; continuation via previous_response_id is only supported on Responses WebSocket v2",
                OpenAiResponsesHttpRequestPolicy.MESSAGE_FUNCTION_CALL_OUTPUT_REQUIRES_ITEM_REFERENCE);
    }

    @Test
    @DisplayName("Responses validator route applicability is centralized")
    void routeApplicabilityIsCentralized() {
        assertTrue(OpenAiResponsesHttpRequestPolicy.appliesToClientFormat(GatewayProtocolFormat.RESPONSES.id()));
        assertFalse(OpenAiResponsesHttpRequestPolicy.appliesToClientFormat(GatewayProtocolFormat.MESSAGES.id()));
        assertFalse(OpenAiResponsesHttpRequestPolicy.appliesToClientFormat(GatewayProtocolFormat.CHAT_COMPLETIONS.id()));
    }
}
