package com.landgate.trigger.gateway.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.types.gateway.OpenAiAnthropicMessagesCompatPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAI compat todo guard tests")
class OpenAiCompatTodoGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("inserts todo guard after existing developer messages")
    void insertsAfterExistingDeveloperMessages() throws Exception {
        OpenAiCompatTodoGuard.GuardResult result = OpenAiCompatTodoGuard.appendToResponsesBody("""
                {
                  "input":[
                    {"type":"message","role":"developer","content":[{"type":"input_text","text":"Project rules"}]},
                    {"type":"message","role":"user","content":[{"type":"input_text","text":"hello"}]}
                  ]
                }""");
        JsonNode input = JSON.readTree(result.body()).get("input");

        assertTrue(result.inserted());
        assertEquals("Project rules", input.get(0).get("content").get(0).get("text").asText());
        assertEquals("developer", input.get(1).get("role").asText());
        assertTrue(input.get(1).get("content").get(0).get("text").asText()
                .contains(OpenAiAnthropicMessagesCompatPolicy.TODO_GUARD_MARKER));
        assertEquals("user", input.get(2).get("role").asText());
    }

    @Test
    @DisplayName("does not duplicate existing todo guard")
    void doesNotDuplicateExistingGuard() throws Exception {
        String body = """
                {
                  "input":[
                    {"type":"message","role":"developer","content":[{"type":"input_text","text":"%s"}]},
                    {"type":"message","role":"user","content":[{"type":"input_text","text":"hello"}]}
                  ]
                }""".formatted(OpenAiAnthropicMessagesCompatPolicy.TODO_GUARD_MARKER);

        OpenAiCompatTodoGuard.GuardResult result = OpenAiCompatTodoGuard.appendToResponsesBody(body);

        assertFalse(result.inserted());
        assertEquals(2, JSON.readTree(result.body()).get("input").size());
    }
}
