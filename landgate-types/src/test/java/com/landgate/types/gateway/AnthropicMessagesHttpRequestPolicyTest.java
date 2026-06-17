package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Anthropic Messages HTTP request policy tests")
class AnthropicMessagesHttpRequestPolicyTest {

    @Test
    @DisplayName("Sub2API-compatible HTTP Messages validation messages are centralized")
    void validationMessagesAreCentralized() {
        assertEquals(400, AnthropicMessagesHttpRequestPolicy.STATUS_BAD_REQUEST);
        assertEquals("invalid_request_error", AnthropicMessagesHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST);
        assertEquals("Request body is empty", AnthropicMessagesHttpRequestPolicy.MESSAGE_EMPTY_BODY);
        assertEquals("Failed to parse request body", AnthropicMessagesHttpRequestPolicy.MESSAGE_PARSE_BODY_FAILED);
        assertEquals("model is required", AnthropicMessagesHttpRequestPolicy.MESSAGE_MODEL_REQUIRED);
        assertEquals("invalid stream field type", AnthropicMessagesHttpRequestPolicy.MESSAGE_INVALID_STREAM_TYPE);
    }

    @Test
    @DisplayName("Messages validator route applicability is centralized")
    void routeApplicabilityIsCentralized() {
        assertTrue(AnthropicMessagesHttpRequestPolicy.appliesToClientFormat(GatewayProtocolFormat.MESSAGES.id()));
        assertFalse(AnthropicMessagesHttpRequestPolicy.appliesToClientFormat(GatewayProtocolFormat.RESPONSES.id()));
        assertTrue(AnthropicMessagesHttpRequestPolicy.isNativeMessagesRoute(
                GatewayProtocolFormat.MESSAGES.id(), GatewayProtocolFormat.MESSAGES.id()));
        assertFalse(AnthropicMessagesHttpRequestPolicy.isNativeMessagesRoute(
                GatewayProtocolFormat.MESSAGES.id(), GatewayProtocolFormat.RESPONSES.id()));
    }
}
