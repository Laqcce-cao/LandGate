package com.landgate.trigger.gateway;

import com.landgate.trigger.gateway.usage.AnthropicUsageParser;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AnthropicUsageParser unit tests for SSE framing compatibility.
 */
@DisplayName("AnthropicUsageParser 测试")
class AnthropicUsageParserTest {

    private final AnthropicUsageParser parser = new AnthropicUsageParser();

    @Test
    @DisplayName("流式终止识别兼容 data: 后不同空白格式")
    void isStreamDoneAcceptsTolerantDataLineForms() {
        assertTrue(parser.isStreamDone("data: {\"type\":\"message_stop\"}"));
        assertTrue(parser.isStreamDone("data:{\"type\":\"message_stop\"}"));
        assertTrue(parser.isStreamDone("data:\t{\"type\":\"message_stop\"}"));
        assertTrue(parser.isStreamDone("data: [DONE]"));
        assertTrue(parser.isStreamDone("data:[DONE]"));
        assertFalse(parser.isStreamDone("data: {\"type\":\"content_block_delta\"}"));
        assertFalse(parser.isStreamDone("event: message_stop"));
    }

    @Test
    @DisplayName("非流式 usage 兼容 cached_tokens 并从嵌套 cache_creation 补聚合写入")
    void nonStreamingUsageReconcilesCachedTokensAndCacheCreationBreakdown() {
        UsageTokens usage = parser.parseNonStreaming("""
                {
                  "usage":{
                    "input_tokens":12,
                    "output_tokens":4,
                    "cached_tokens":9,
                    "cache_creation":{
                      "ephemeral_5m_input_tokens":3,
                      "ephemeral_1h_input_tokens":5
                    }
                  }
                }
                """);

        assertEquals(12, usage.getInputTokens());
        assertEquals(4, usage.getOutputTokens());
        assertEquals(9, usage.getCacheReadTokens());
        assertEquals(8, usage.getCacheCreationTokens());
        assertEquals(3, usage.getCacheCreation5mTokens());
        assertEquals(5, usage.getCacheCreation1hTokens());
    }

    @Test
    @DisplayName("流式 usage 兼容 message_start/message_delta 的 cached_tokens")
    void streamingUsageReconcilesCachedTokens() {
        UsageTokens start = parser.parseSSELine("""
                {"type":"message_start","message":{"usage":{"input_tokens":12,"cached_tokens":9,
                "cache_creation":{"ephemeral_5m_input_tokens":3,"ephemeral_1h_input_tokens":5}}}}
                """);
        UsageTokens delta = parser.parseSSELine("""
                {"type":"message_delta","usage":{"output_tokens":4,"cached_tokens":11,
                "cache_creation":{"ephemeral_5m_input_tokens":6,"ephemeral_1h_input_tokens":7}}}
                """);

        assertEquals(9, start.getCacheReadTokens());
        assertEquals(8, start.getCacheCreationTokens());
        assertEquals(11, delta.getCacheReadTokens());
        assertEquals(13, delta.getCacheCreationTokens());
    }
}
