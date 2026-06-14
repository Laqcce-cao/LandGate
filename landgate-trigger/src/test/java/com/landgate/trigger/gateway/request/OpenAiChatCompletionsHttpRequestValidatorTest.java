package com.landgate.trigger.gateway.request;

import com.landgate.types.gateway.OpenAiChatCompletionsHttpRequestPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAI Chat Completions HTTP request validator tests")
class OpenAiChatCompletionsHttpRequestValidatorTest {

    private final OpenAiChatCompletionsHttpRequestValidator validator = new OpenAiChatCompletionsHttpRequestValidator();

    @Test
    @DisplayName("Chat Completions HTTP rejects empty request body")
    void rejectsEmptyBody() {
        var result = validator.validate("", "chat_completions");

        assertFalse(result.accepted());
        assertEquals(400, result.status());
        assertEquals(OpenAiChatCompletionsHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST, result.code());
        assertEquals("Request body is empty", result.message());
    }

    @Test
    @DisplayName("Chat Completions HTTP rejects invalid JSON body")
    void rejectsInvalidJson() {
        var result = validator.validate("{", "chat_completions");

        assertFalse(result.accepted());
        assertEquals(400, result.status());
        assertEquals("Failed to parse request body", result.message());
    }

    @Test
    @DisplayName("Chat Completions HTTP requires textual non-empty model")
    void requiresModel() {
        var missing = validator.validate("""
                {"messages":[{"role":"user","content":"Hi"}]}""", "chat_completions");
        var empty = validator.validate("""
                {"model":"","messages":[{"role":"user","content":"Hi"}]}""", "chat_completions");
        var wrongType = validator.validate("""
                {"model":123,"messages":[{"role":"user","content":"Hi"}]}""", "chat_completions");

        assertFalse(missing.accepted());
        assertFalse(empty.accepted());
        assertFalse(wrongType.accepted());
        assertEquals("model is required", missing.message());
        assertEquals("model is required", empty.message());
        assertEquals("model is required", wrongType.message());
    }

    @Test
    @DisplayName("Chat Completions HTTP accepts valid request")
    void acceptsValidRequest() {
        var result = validator.validate("""
                {
                  "model":"gpt-5.5",
                  "messages":[{"role":"user","content":"Hi"}]
                }""", "chat_completions");

        assertTrue(result.accepted());
    }

    @Test
    @DisplayName("Chat Completions HTTP does not apply Responses-only stream type validation")
    void doesNotRejectStringStream() {
        var result = validator.validate("""
                {
                  "model":"gpt-5.5",
                  "stream":"false",
                  "messages":[{"role":"user","content":"Hi"}]
                }""", "chat_completions");

        assertTrue(result.accepted());
    }

    @Test
    @DisplayName("Validator ignores non-Chat client formats")
    void ignoresNonChatFormats() {
        var result = validator.validate("", "responses");

        assertTrue(result.accepted());
    }
}
