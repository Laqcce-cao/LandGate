package com.landgate.trigger.gateway.request;

import com.landgate.types.gateway.OpenAiPreviousResponseIdPolicy;
import com.landgate.types.gateway.OpenAiResponsesHttpRequestPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAI Responses HTTP request validator tests")
class OpenAiResponsesHttpRequestValidatorTest {

    private final OpenAiResponsesHttpRequestValidator validator = new OpenAiResponsesHttpRequestValidator();

    @Test
    @DisplayName("Responses HTTP rejects message-id previous_response_id with Sub2API diagnostic")
    void responsesHttpRejectsMessagePreviousResponseId() {
        var result = validator.validate("""
                {
                  "model":"gpt-5.5",
                  "previous_response_id":"msg_abc",
                  "input":"Hi"
                }""", "responses");

        assertFalse(result.accepted());
        assertEquals(400, result.status());
        assertEquals(OpenAiResponsesHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST, result.code());
        assertEquals(OpenAiPreviousResponseIdPolicy.KIND_MESSAGE_ID, result.previousResponseIdKind());
        assertEquals("previous_response_id must be a response.id (resp_*), not a message id",
                result.message());
    }

    @Test
    @DisplayName("Responses HTTP rejects response-id previous_response_id because HTTP is not WebSocket v2")
    void responsesHttpRejectsResponsePreviousResponseId() {
        var result = validator.validate("""
                {
                  "model":"gpt-5.5",
                  "previous_response_id":"resp_abc",
                  "input":"Hi"
                }""", "responses");

        assertFalse(result.accepted());
        assertEquals(400, result.status());
        assertEquals(OpenAiPreviousResponseIdPolicy.KIND_RESPONSE_ID, result.previousResponseIdKind());
        assertEquals("previous_response_id is only supported on Responses WebSocket v2",
                result.message());
    }

    @Test
    @DisplayName("Validator does not apply to translated non-Responses client formats")
    void ignoresNonResponsesClientFormats() {
        var result = validator.validate("""
                {
                  "model":"claude-sonnet-4-5",
                  "previous_response_id":"resp_abc",
                  "messages":[{"role":"user","content":"Hi"}]
                }""", "messages");

        assertTrue(result.accepted());
    }

    @Test
    @DisplayName("Responses HTTP accepts requests without previous_response_id")
    void acceptsResponsesRequestWithoutPreviousResponseId() {
        var result = validator.validate("""
                {
                  "model":"gpt-5.5",
                  "input":"Hi"
                }""", "responses");

        assertTrue(result.accepted());
    }

    @Test
    @DisplayName("Responses HTTP rejects empty request body")
    void responsesHttpRejectsEmptyBody() {
        var result = validator.validate("", "responses");

        assertFalse(result.accepted());
        assertEquals(400, result.status());
        assertEquals("Request body is empty", result.message());
    }

    @Test
    @DisplayName("Responses HTTP rejects invalid JSON body")
    void responsesHttpRejectsInvalidJson() {
        var result = validator.validate("{", "responses");

        assertFalse(result.accepted());
        assertEquals(400, result.status());
        assertEquals("Failed to parse request body", result.message());
    }

    @Test
    @DisplayName("Responses HTTP requires textual non-empty model")
    void responsesHttpRequiresModel() {
        var missing = validator.validate("""
                {"input":"Hi"}""", "responses");
        var empty = validator.validate("""
                {"model":" ","input":"Hi"}""", "responses");
        var wrongType = validator.validate("""
                {"model":123,"input":"Hi"}""", "responses");

        assertFalse(missing.accepted());
        assertFalse(empty.accepted());
        assertFalse(wrongType.accepted());
        assertEquals("model is required", missing.message());
        assertEquals("model is required", empty.message());
        assertEquals("model is required", wrongType.message());
    }

    @Test
    @DisplayName("Responses HTTP requires stream to be boolean when present")
    void responsesHttpRequiresBooleanStream() {
        var result = validator.validate("""
                {
                  "model":"gpt-5.5",
                  "stream":"false",
                  "input":"Hi"
                }""", "responses");

        assertFalse(result.accepted());
        assertEquals(400, result.status());
        assertEquals("invalid stream field type", result.message());
    }

    @Test
    @DisplayName("Responses HTTP rejects function_call_output without call_id")
    void responsesHttpRejectsFunctionCallOutputWithoutCallId() {
        var result = validator.validate("""
                {
                  "model":"gpt-5.5",
                  "input":[{"type":"function_call_output","output":"{}"}]
                }""", "responses");

        assertFalse(result.accepted());
        assertEquals(400, result.status());
        assertEquals("function_call_output requires call_id on HTTP requests; continuation via previous_response_id is only supported on Responses WebSocket v2",
                result.message());
    }

    @Test
    @DisplayName("Responses HTTP rejects function_call_output without matching context")
    void responsesHttpRejectsFunctionCallOutputWithoutItemReference() {
        var result = validator.validate("""
                {
                  "model":"gpt-5.5",
                  "input":[{"type":"function_call_output","call_id":"call_1","output":"{}"}]
                }""", "responses");

        assertFalse(result.accepted());
        assertEquals(400, result.status());
        assertEquals("function_call_output requires item_reference ids matching each call_id on HTTP requests; continuation via previous_response_id is only supported on Responses WebSocket v2",
                result.message());
    }

    @Test
    @DisplayName("Responses HTTP accepts function_call_output with same-input function_call context")
    void responsesHttpAcceptsFunctionCallOutputWithToolCallContext() {
        var result = validator.validate("""
                {
                  "model":"gpt-5.5",
                  "input":[
                    {"type":"function_call","call_id":"call_1","name":"fn","arguments":"{}"},
                    {"type":"function_call_output","call_id":"call_1","output":"ok"}
                  ]
                }""", "responses");

        assertTrue(result.accepted());
    }

    @Test
    @DisplayName("Responses HTTP accepts function_call_output when item_reference covers every call_id")
    void responsesHttpAcceptsFunctionCallOutputWithItemReferences() {
        var result = validator.validate("""
                {
                  "model":"gpt-5.5",
                  "input":[
                    {"type":"item_reference","id":"call_1"},
                    {"type":"function_call_output","call_id":"call_1","output":"ok"}
                  ]
                }""", "responses");

        assertTrue(result.accepted());
    }
}
