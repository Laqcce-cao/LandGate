package com.landgate.trigger.gateway;

import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.trigger.gateway.usage.ResponsesUsageParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ResponsesUsageParser 单元测试 —— 验证 OpenAI Responses 用量字段映射。
 */
@DisplayName("ResponsesUsageParser 测试")
class ResponsesUsageParserTest {

    private final ResponsesUsageParser parser = new ResponsesUsageParser();

    @Test
    @DisplayName("非流式 usage 将 cached_tokens 计入缓存读取并从输入中扣除")
    void parseNonStreamingSubtractsCachedTokens() {
        String responseBody = """
                {
                  "usage": {
                    "input_tokens": 100,
                    "output_tokens": 20,
                    "input_tokens_details": {"cached_tokens": 80}
                  }
                }""";

        UsageTokens usage = parser.parseNonStreaming(responseBody);

        assertEquals(20, usage.getInputTokens());
        assertEquals(20, usage.getOutputTokens());
        assertEquals(80, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("非流式 response.usage 也会被解析")
    void parseNonStreamingExtractsNestedResponseUsage() {
        String responseBody = """
                {
                  "response": {
                    "usage": {
                      "input_tokens": 40,
                      "output_tokens": 8,
                      "input_tokens_details": {"cached_tokens": 15}
                    }
                  }
                }""";

        UsageTokens usage = parser.parseNonStreaming(responseBody);

        assertEquals(25, usage.getInputTokens());
        assertEquals(8, usage.getOutputTokens());
        assertEquals(15, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("流式 response.completed usage 将 cached_tokens 计入缓存读取并从输入中扣除")
    void parseSSELineSubtractsCachedTokensFromResponseCompleted() {
        String sseData = """
                {
                  "type": "response.completed",
                  "response": {
                    "usage": {
                      "input_tokens": 100,
                      "output_tokens": 5,
                      "input_tokens_details": {"cached_tokens": 80}
                    }
                  }
                }""";

        UsageTokens usage = parser.parseSSELine(sseData);

        assertEquals(20, usage.getInputTokens());
        assertEquals(5, usage.getOutputTokens());
        assertEquals(80, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("流式终止事件顶层 usage 也会被解析")
    void parseSSELineExtractsTopLevelUsageFromTerminalEvent() {
        String sseData = """
                {
                  "type": "response.completed",
                  "usage": {
                    "input_tokens": 60,
                    "output_tokens": 9,
                    "input_tokens_details": {"cached_tokens": 20}
                  }
                }""";

        UsageTokens usage = parser.parseSSELine(sseData);

        assertEquals(40, usage.getInputTokens());
        assertEquals(9, usage.getOutputTokens());
        assertEquals(20, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("流式 response.done usage 会被解析")
    void parseSSELineExtractsUsageFromResponseDone() {
        String sseData = """
                {
                  "type": "response.done",
                  "response": {
                    "usage": {
                      "input_tokens": 30,
                      "output_tokens": 7,
                      "input_tokens_details": {"cached_tokens": 10}
                    }
                  }
                }""";

        UsageTokens usage = parser.parseSSELine(sseData);

        assertEquals(20, usage.getInputTokens());
        assertEquals(7, usage.getOutputTokens());
        assertEquals(10, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("流式 response.incomplete usage 会被解析")
    void parseSSELineExtractsUsageFromResponseIncomplete() {
        String sseData = """
                {
                  "type": "response.incomplete",
                  "response": {
                    "usage": {
                      "input_tokens": 12,
                      "output_tokens": 4
                    }
                  }
                }""";

        UsageTokens usage = parser.parseSSELine(sseData);

        assertEquals(12, usage.getInputTokens());
        assertEquals(4, usage.getOutputTokens());
        assertEquals(0, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("流式 response.failed usage 会被解析")
    void parseSSELineExtractsUsageFromResponseFailed() {
        String sseData = """
                {
                  "type": "response.failed",
                  "response": {
                    "usage": {
                      "input_tokens": 50,
                      "output_tokens": 10
                    }
                  }
                }""";

        UsageTokens usage = parser.parseSSELine(sseData);

        assertEquals(50, usage.getInputTokens());
        assertEquals(10, usage.getOutputTokens());
    }

    @Test
    @DisplayName("非 response.completed 事件不产生用量")
    void parseSSELineReturnsNullForNonUsageEvent() {
        String sseData = """
                {
                  "type": "response.output_text.delta",
                  "delta": "hello"
                }""";

        assertNull(parser.parseSSELine(sseData));
    }

    @Test
    @DisplayName("流式终止事件识别 response 结束状态")
    void isStreamDoneRecognizesResponsesTerminalEvents() {
        assertTrue(parser.isStreamDone("data: {\"type\":\"response.completed\"}"));
        assertTrue(parser.isStreamDone("data: {\"type\":\"response.done\"}"));
        assertTrue(parser.isStreamDone("data: {\"type\":\"response.failed\"}"));
        assertTrue(parser.isStreamDone("data: {\"type\":\"response.incomplete\"}"));
        assertTrue(parser.isStreamDone("data: [DONE]"));
        assertFalse(parser.isStreamDone("data: {\"type\":\"response.output_text.delta\"}"));
        assertFalse(parser.isStreamDone("event: response.completed"));
    }
}
