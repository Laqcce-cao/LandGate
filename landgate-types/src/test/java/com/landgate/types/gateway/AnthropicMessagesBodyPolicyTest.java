package com.landgate.types.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AnthropicMessagesBodyPolicy 测试")
class AnthropicMessagesBodyPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("cache_control 判断集中维护")
    void detectsCacheControl() throws Exception {
        assertTrue(AnthropicMessagesBodyPolicy.hasCacheControl(
                JSON.readTree("{\"cache_control\":{\"type\":\"ephemeral\"}}")));
        assertFalse(AnthropicMessagesBodyPolicy.hasCacheControl(
                JSON.readTree("{\"type\":\"text\"}")));
        assertFalse(AnthropicMessagesBodyPolicy.hasCacheControl(null));
    }

    @Test
    @DisplayName("thinking 类型判断集中维护")
    void detectsThinkingTypes() {
        assertTrue(AnthropicMessagesBodyPolicy.isThinkingType("thinking"));
        assertTrue(AnthropicMessagesBodyPolicy.isThinkingType("redacted_thinking"));
        assertFalse(AnthropicMessagesBodyPolicy.isThinkingType("text"));
        assertEquals("text", AnthropicMessagesBodyPolicy.TYPE_TEXT);
        assertEquals("ephemeral", AnthropicMessagesBodyPolicy.CACHE_CONTROL_TYPE_EPHEMERAL);
    }

    @Test
    @DisplayName("document/source/tool_choice 字段和值集中维护")
    void documentSourceAndToolChoiceFactsAreCentralized() {
        assertEquals("source", AnthropicMessagesBodyPolicy.FIELD_SOURCE);
        assertEquals("title", AnthropicMessagesBodyPolicy.FIELD_TITLE);
        assertEquals("media_type", AnthropicMessagesBodyPolicy.FIELD_MEDIA_TYPE);
        assertEquals("file_id", AnthropicMessagesBodyPolicy.FIELD_FILE_ID);
        assertEquals("cached_tokens", AnthropicMessagesBodyPolicy.FIELD_CACHED_TOKENS);
        assertEquals("url", AnthropicMessagesBodyPolicy.TYPE_URL);
        assertEquals("base64", AnthropicMessagesBodyPolicy.TYPE_BASE64);
        assertEquals("file", AnthropicMessagesBodyPolicy.TYPE_FILE);
        assertEquals("document", AnthropicMessagesBodyPolicy.TYPE_DOCUMENT);
        assertEquals("none", AnthropicMessagesBodyPolicy.TYPE_TOOL_CHOICE_NONE);
        assertEquals("any", AnthropicMessagesBodyPolicy.TYPE_ANY);
    }
}
