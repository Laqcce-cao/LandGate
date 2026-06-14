package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AnthropicMessagesSsePolicy 测试")
class AnthropicMessagesSsePolicyTest {

    @Test
    @DisplayName("SSE 事件和错误默认值集中维护")
    void sseFactsAreCentralized() {
        assertEquals("message_start", AnthropicMessagesSsePolicy.EVENT_MESSAGE_START);
        assertEquals("message_delta", AnthropicMessagesSsePolicy.EVENT_MESSAGE_DELTA);
        assertEquals("message_stop", AnthropicMessagesSsePolicy.EVENT_MESSAGE_STOP);
        assertEquals("error", AnthropicMessagesSsePolicy.EVENT_ERROR);
        assertEquals("api_error", AnthropicMessagesSsePolicy.DEFAULT_ERROR_TYPE);
        assertEquals("Anthropic stream error", AnthropicMessagesSsePolicy.DEFAULT_ERROR_MESSAGE);
    }

    @Test
    @DisplayName("SSE data payload 解析兼容 data: 和 data:space")
    void extractsDataPayload() {
        assertEquals("{\"type\":\"message_stop\"}",
                AnthropicMessagesSsePolicy.extractDataPayload("data: {\"type\":\"message_stop\"}"));
        assertEquals("[DONE]", AnthropicMessagesSsePolicy.extractDataPayload("data:\t[DONE]"));
        assertTrue(AnthropicMessagesSsePolicy.isDoneSentinel(" [DONE] "));
        assertFalse(AnthropicMessagesSsePolicy.isDoneSentinel("{}"));
    }
}
