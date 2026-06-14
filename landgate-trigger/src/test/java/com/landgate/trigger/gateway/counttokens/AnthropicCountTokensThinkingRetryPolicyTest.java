package com.landgate.trigger.gateway.counttokens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AnthropicCountTokensThinkingRetryPolicy")
class AnthropicCountTokensThinkingRetryPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final AnthropicCountTokensThinkingRetryPolicy policy = new AnthropicCountTokensThinkingRetryPolicy();

    @Test
    @DisplayName("detects Sub2API thinking signature retry errors")
    void detectsThinkingSignatureRetryErrors() {
        assertTrue(policy.shouldRetry(400,
                "{\"error\":{\"message\":\"Invalid `signature` in `thinking` block\"}}"));
        assertTrue(policy.shouldRetry(400,
                "{\"error\":{\"message\":\"Expected `thinking` or `redacted_thinking`, but found `text`\"}}"));
        assertTrue(policy.shouldRetry(400,
                "{\"error\":{\"message\":\"messages: text content blocks must be non-empty\"}}"));
        assertFalse(policy.shouldRetry(400,
                "{\"error\":{\"message\":\"model is required\"}}"));
        assertFalse(policy.shouldRetry(429,
                "{\"error\":{\"message\":\"Invalid `signature` in `thinking` block\"}}"));
    }

    @Test
    @DisplayName("filters thinking blocks for retry and disables thinking constraints")
    void filtersThinkingBlocksForRetry() throws Exception {
        String body = """
                {
                  "model":"claude-sonnet-4-5",
                  "thinking":{"type":"enabled","budget_tokens":1024},
                  "context_management":{"edits":[{"type":"clear_thinking_20251015","keep":"all"}]},
                  "messages":[
                    {"role":"assistant","content":[
                      {"type":"thinking","thinking":"private","signature":"bad"},
                      {"type":"redacted_thinking","data":"secret"},
                      {"type":"text","text":""},
                      {"type":"text","text":"visible"}
                    ]},
                    {"role":"user","content":[]}
                  ]
                }""";

        JsonNode filtered = JSON.readTree(policy.filterBodyForRetry(body));

        assertFalse(filtered.has("thinking"));
        assertFalse(filtered.path("context_management").path("edits").isArray());
        JsonNode assistantContent = filtered.path("messages").get(0).path("content");
        assertEquals("text", assistantContent.get(0).path("type").asText());
        assertEquals("private", assistantContent.get(0).path("text").asText());
        assertEquals("visible", assistantContent.get(1).path("text").asText());
        assertEquals(2, assistantContent.size());
        assertEquals("(content removed)",
                filtered.path("messages").get(1).path("content").get(0).path("text").asText());
    }
}
