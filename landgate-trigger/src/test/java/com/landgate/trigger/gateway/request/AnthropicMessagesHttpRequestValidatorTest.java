package com.landgate.trigger.gateway.request;

import com.landgate.types.gateway.AnthropicMessagesHttpRequestPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Anthropic Messages HTTP request validator tests")
class AnthropicMessagesHttpRequestValidatorTest {

    private final AnthropicMessagesHttpRequestValidator validator = new AnthropicMessagesHttpRequestValidator();

    @Test
    @DisplayName("Messages HTTP rejects empty request body")
    void rejectsEmptyBody() {
        var result = validator.validate("", "messages");

        assertFalse(result.accepted());
        assertEquals(400, result.status());
        assertEquals(AnthropicMessagesHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST, result.code());
        assertEquals("Request body is empty", result.message());
    }

    @Test
    @DisplayName("Messages HTTP rejects invalid JSON body")
    void rejectsInvalidJson() {
        var result = validator.validate("{", "messages");

        assertFalse(result.accepted());
        assertEquals(400, result.status());
        assertEquals("Failed to parse request body", result.message());
    }

    @Test
    @DisplayName("Messages HTTP requires textual non-empty model")
    void requiresModel() {
        var missing = validator.validate("""
                {"messages":[{"role":"user","content":"Hi"}]}""", "messages");
        var empty = validator.validate("""
                {"model":"","messages":[{"role":"user","content":"Hi"}]}""", "messages");
        var wrongType = validator.validate("""
                {"model":123,"messages":[{"role":"user","content":"Hi"}]}""", "messages");

        assertFalse(missing.accepted());
        assertFalse(empty.accepted());
        assertFalse(wrongType.accepted());
        assertEquals("model is required", missing.message());
        assertEquals("model is required", empty.message());
        assertEquals("model is required", wrongType.message());
    }

    @Test
    @DisplayName("Messages HTTP common validation does not reject non-boolean stream before account routing")
    void commonValidationDoesNotRejectStringStream() {
        var result = validator.validate("""
                {
                  "model":"claude-sonnet-4-5",
                  "stream":"false",
                  "messages":[{"role":"user","content":"Hi"}]
                }""", "messages");

        assertTrue(result.accepted());
    }

    @Test
    @DisplayName("Native Anthropic Messages route rejects non-boolean stream like Sub2API Anthropic parser")
    void nativeRouteRejectsInvalidStreamType() {
        var result = validator.validateNativeStreamType("""
                {
                  "model":"claude-sonnet-4-5",
                  "stream":"false",
                  "messages":[{"role":"user","content":"Hi"}]
                }""", "messages", "messages");

        assertFalse(result.accepted());
        assertEquals(400, result.status());
        assertEquals("invalid stream field type", result.message());
    }

    @Test
    @DisplayName("OpenAI Messages compatibility route keeps Sub2API's lenient stream handling")
    void openAiCompatRouteDoesNotRejectStringStream() {
        var result = validator.validateNativeStreamType("""
                {
                  "model":"gpt-5.5",
                  "stream":"false",
                  "messages":[{"role":"user","content":"Hi"}]
                }""", "messages", "responses");

        assertTrue(result.accepted());
    }

    @Test
    @DisplayName("Messages HTTP accepts valid request")
    void acceptsValidRequest() {
        var result = validator.validate("""
                {
                  "model":"claude-sonnet-4-5",
                  "stream":false,
                  "messages":[{"role":"user","content":"Hi"}]
                }""", "messages");

        assertTrue(result.accepted());
    }

    @Test
    @DisplayName("Validator ignores non-Messages client formats")
    void ignoresNonMessagesFormats() {
        var result = validator.validate("", "responses");

        assertTrue(result.accepted());
    }
}
