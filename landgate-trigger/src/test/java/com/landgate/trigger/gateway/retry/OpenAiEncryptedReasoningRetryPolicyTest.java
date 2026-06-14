package com.landgate.trigger.gateway.retry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenAiEncryptedReasoningRetryPolicy")
class OpenAiEncryptedReasoningRetryPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final OpenAiEncryptedReasoningRetryPolicy policy = new OpenAiEncryptedReasoningRetryPolicy();

    @Test
    @DisplayName("only retries 400 invalid_encrypted_content errors")
    void onlyRetriesInvalidEncryptedContentErrors() {
        assertTrue(policy.shouldRetry(400, """
                {"error":{"code":"invalid_encrypted_content","message":"bad encrypted content"}}
                """));
        assertFalse(policy.shouldRetry(400, """
                {"error":{"code":"invalid_request_error","message":"bad request"}}
                """));
        assertFalse(policy.shouldRetry(500, """
                {"error":{"code":"invalid_encrypted_content","message":"bad encrypted content"}}
                """));
    }

    @Test
    @DisplayName("removes encrypted_content from reasoning input items")
    void removesEncryptedContentFromReasoningInputItems() throws Exception {
        OpenAiEncryptedReasoningRetryPolicy.SanitizedBody sanitized = policy.sanitizePreparedBody("""
                {
                  "model":"gpt-5.5",
                  "input":[
                    {"type":"message","role":"user","content":"hi"},
                    {"type":"reasoning","id":"rsn_1","encrypted_content":"secret","summary":[]},
                    {"type":"reasoning","encrypted_content":"drop-me"}
                  ]
                }
                """);

        JsonNode root = JSON.readTree(sanitized.body());

        assertTrue(sanitized.changed());
        assertEquals(2, root.get("input").size());
        assertEquals("message", root.get("input").get(0).get("type").asText());
        assertEquals("reasoning", root.get("input").get(1).get("type").asText());
        assertEquals("rsn_1", root.get("input").get(1).get("id").asText());
        assertFalse(root.get("input").get(1).has("encrypted_content"));
    }

    @Test
    @DisplayName("removes object input when reasoning encrypted_content has no remaining payload")
    void removesObjectInputWhenReasoningItemHasNoRemainingPayload() throws Exception {
        OpenAiEncryptedReasoningRetryPolicy.SanitizedBody sanitized = policy.sanitizePreparedBody("""
                {"model":"gpt-5.5","input":{"type":"reasoning","encrypted_content":"secret"}}
                """);

        JsonNode root = JSON.readTree(sanitized.body());

        assertTrue(sanitized.changed());
        assertFalse(root.has("input"));
    }

    @Test
    @DisplayName("leaves bodies without encrypted reasoning unchanged")
    void leavesBodiesWithoutEncryptedReasoningUnchanged() {
        String body = """
                {"model":"gpt-5.5","input":[{"type":"reasoning","summary":[]}]}
                """;

        OpenAiEncryptedReasoningRetryPolicy.SanitizedBody sanitized = policy.sanitizePreparedBody(body);

        assertFalse(sanitized.changed());
        assertEquals(body, sanitized.body());
    }
}
