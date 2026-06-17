package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAI Chat Completions HTTP request policy tests")
class OpenAiChatCompletionsHttpRequestPolicyTest {

    @Test
    @DisplayName("Sub2API-compatible HTTP Chat Completions validation messages are centralized")
    void validationMessagesAreCentralized() {
        assertEquals(400, OpenAiChatCompletionsHttpRequestPolicy.STATUS_BAD_REQUEST);
        assertEquals("invalid_request_error", OpenAiChatCompletionsHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST);
        assertEquals("Request body is empty", OpenAiChatCompletionsHttpRequestPolicy.MESSAGE_EMPTY_BODY);
        assertEquals("Failed to parse request body", OpenAiChatCompletionsHttpRequestPolicy.MESSAGE_PARSE_BODY_FAILED);
        assertEquals("model is required", OpenAiChatCompletionsHttpRequestPolicy.MESSAGE_MODEL_REQUIRED);
    }

    @Test
    @DisplayName("Chat Completions validator route applicability is centralized")
    void routeApplicabilityIsCentralized() {
        assertTrue(OpenAiChatCompletionsHttpRequestPolicy.appliesToClientFormat(
                GatewayProtocolFormat.CHAT_COMPLETIONS.id()));
        assertFalse(OpenAiChatCompletionsHttpRequestPolicy.appliesToClientFormat(
                GatewayProtocolFormat.RESPONSES.id()));
        assertFalse(OpenAiChatCompletionsHttpRequestPolicy.appliesToClientFormat(
                GatewayProtocolFormat.MESSAGES.id()));
    }
}
