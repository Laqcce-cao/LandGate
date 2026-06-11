package com.landgate.trigger.gateway;

import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.trigger.gateway.usage.AnthropicUsageParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("AnthropicUsageParser 测试")
class AnthropicUsageParserTest {

    private final AnthropicUsageParser parser = new AnthropicUsageParser();

    @Test
    @DisplayName("非流式缺少 usage 时返回 null")
    void parseNonStreamingReturnsNullWithoutUsage() {
        String responseBody = """
                {
                  "id": "msg_1",
                  "type": "message",
                  "content": []
                }""";

        assertNull(parser.parseNonStreaming(responseBody));
    }

    @Test
    @DisplayName("非流式 usage 全 0 时保留已解析结果")
    void parseNonStreamingKeepsZeroUsageWhenFieldExists() {
        String responseBody = """
                {
                  "usage": {
                    "input_tokens": 0,
                    "output_tokens": 0
                  }
                }""";

        UsageTokens usage = parser.parseNonStreaming(responseBody);

        assertEquals(0, usage.getInputTokens());
        assertEquals(0, usage.getOutputTokens());
        assertFalse(usage.hasUsage());
    }

    @Test
    @DisplayName("非流式标准 usage 正常解析")
    void parseNonStreamingExtractsUsage() {
        String responseBody = """
                {
                  "usage": {
                    "input_tokens": 10,
                    "output_tokens": 5,
                    "cache_creation_input_tokens": 2,
                    "cache_read_input_tokens": 3,
                    "cache_creation": {
                      "ephemeral_5m_input_tokens": 4,
                      "ephemeral_1h_input_tokens": 6
                    }
                  }
                }""";

        UsageTokens usage = parser.parseNonStreaming(responseBody);

        assertEquals(10, usage.getInputTokens());
        assertEquals(5, usage.getOutputTokens());
        assertEquals(2, usage.getCacheCreationTokens());
        assertEquals(3, usage.getCacheReadTokens());
        assertEquals(4, usage.getCacheCreation5mTokens());
        assertEquals(6, usage.getCacheCreation1hTokens());
    }
}
