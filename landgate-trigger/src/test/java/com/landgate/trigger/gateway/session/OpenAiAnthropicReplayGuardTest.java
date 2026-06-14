package com.landgate.trigger.gateway.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.types.gateway.OpenAiCompatSessionPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAI Anthropic replay guard tests")
class OpenAiAnthropicReplayGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final OpenAiAnthropicReplayGuard guard = new OpenAiAnthropicReplayGuard();

    @Test
    @DisplayName("trims old Anthropic messages to Sub2API tail window")
    void trimsOldMessages() throws Exception {
        OpenAiAnthropicReplayGuard.TrimResult result = guard.trimFullReplay(requestWithMessages(15));
        JsonNode root = JSON.readTree(result.body());

        assertTrue(result.trimmed());
        assertEquals(OpenAiCompatSessionPolicy.ANTHROPIC_REPLAY_MAX_TAIL_MESSAGES,
                root.get("messages").size());
        assertEquals("message-03", root.get("messages").get(0).get("content").asText());
        assertEquals("message-14", root.get("messages").get(11).get("content").asText());
    }

    @Test
    @DisplayName("expands trim boundary to keep tool_use and tool_result pair intact")
    void keepsToolBoundaryIntact() throws Exception {
        StringBuilder messages = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            if (i > 0) messages.append(',');
            if (i == 1) {
                messages.append("""
                        {"role":"assistant","content":[{"type":"tool_use","id":"toolu_keep","name":"Read","input":{"file_path":"main.go"}}]}""");
            } else if (i == 3) {
                messages.append("""
                        {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_keep","content":"ok"}]}""");
            } else {
                messages.append("""
                        {"role":"user","content":"message-%02d"}""".formatted(i));
            }
        }

        OpenAiAnthropicReplayGuard.TrimResult result = guard.trimFullReplay("""
                {"model":"claude-sonnet-4-5","messages":[%s]}""".formatted(messages));
        JsonNode root = JSON.readTree(result.body());

        assertTrue(result.trimmed());
        assertEquals(OpenAiCompatSessionPolicy.ANTHROPIC_REPLAY_MAX_TAIL_MESSAGES + 2,
                root.get("messages").size());
        assertEquals("assistant", root.get("messages").get(0).get("role").asText());
        assertEquals("tool_use", root.get("messages").get(0).get("content").get(0).get("type").asText());
        assertEquals("tool_result", root.get("messages").get(2).get("content").get(0).get("type").asText());
    }

    @Test
    @DisplayName("does not trim short or invalid requests")
    void skipsShortAndInvalidRequests() {
        String shortBody = requestWithMessages(OpenAiCompatSessionPolicy.ANTHROPIC_REPLAY_MAX_TAIL_MESSAGES);
        assertFalse(guard.trimFullReplay(shortBody).trimmed());

        OpenAiAnthropicReplayGuard.TrimResult invalid = guard.trimFullReplay("{");
        assertFalse(invalid.trimmed());
        assertEquals("{", invalid.body());
    }

    private static String requestWithMessages(int count) {
        StringBuilder messages = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) messages.append(',');
            messages.append("""
                    {"role":"user","content":"message-%02d"}""".formatted(i));
        }
        return """
                {"model":"claude-sonnet-4-5","messages":[%s]}""".formatted(messages);
    }
}
