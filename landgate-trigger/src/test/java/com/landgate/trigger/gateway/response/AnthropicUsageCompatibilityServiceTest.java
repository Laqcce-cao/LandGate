package com.landgate.trigger.gateway.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Anthropic usage compatibility service")
class AnthropicUsageCompatibilityServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final AnthropicUsageCompatibilityService service = new AnthropicUsageCompatibilityService();

    @Test
    @DisplayName("non-streaming body maps cached_tokens and nested cache_creation into Anthropic canonical fields")
    void nonStreamingBodyIsNormalized() throws Exception {
        String body = service.normalizeNonStreamingBody("""
                {"usage":{"cached_tokens":9,"cache_creation":{"ephemeral_5m_input_tokens":3,"ephemeral_1h_input_tokens":5}}}
                """);

        var usage = JSON.readTree(body).path(AnthropicMessagesBodyPolicy.FIELD_USAGE);
        assertEquals(9, usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_READ_INPUT_TOKENS).asInt());
        assertEquals(8, usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_CREATION_INPUT_TOKENS).asInt());
    }

    @Test
    @DisplayName("SSE message_start and message_delta usage are normalized")
    void sseUsageIsNormalized() throws Exception {
        String start = service.normalizeSseLine("""
                data: {"type":"message_start","message":{"usage":{"cached_tokens":9,"cache_creation":{"ephemeral_5m_input_tokens":3,"ephemeral_1h_input_tokens":5}}}}
                """.trim());
        String delta = service.normalizeSseLine("""
                data: {"type":"message_delta","usage":{"cached_tokens":11,"cache_creation":{"ephemeral_5m_input_tokens":6,"ephemeral_1h_input_tokens":7}}}
                """.trim());

        assertTrue(start.startsWith("data: "));
        assertTrue(delta.startsWith("data: "));
        var startUsage = JSON.readTree(start.substring("data: ".length()))
                .path(AnthropicMessagesBodyPolicy.FIELD_MESSAGE)
                .path(AnthropicMessagesBodyPolicy.FIELD_USAGE);
        var deltaUsage = JSON.readTree(delta.substring("data: ".length()))
                .path(AnthropicMessagesBodyPolicy.FIELD_USAGE);
        assertEquals(9, startUsage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_READ_INPUT_TOKENS).asInt());
        assertEquals(8, startUsage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_CREATION_INPUT_TOKENS).asInt());
        assertEquals(11, deltaUsage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_READ_INPUT_TOKENS).asInt());
        assertEquals(13, deltaUsage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_CREATION_INPUT_TOKENS).asInt());
    }
}
