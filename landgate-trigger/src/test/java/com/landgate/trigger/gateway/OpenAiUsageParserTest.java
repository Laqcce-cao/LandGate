package com.landgate.trigger.gateway;

import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.trigger.gateway.usage.OpenAiUsageParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("OpenAiUsageParser 测试")
class OpenAiUsageParserTest {

    private final OpenAiUsageParser parser = new OpenAiUsageParser();

    @Test
    @DisplayName("流式顶层 usage 按 Chat Completions 字段解析")
    void parseSSELineExtractsTopLevelChatUsage() {
        String sseData = """
                {
                  "choices": [],
                  "usage": {
                    "prompt_tokens": 30,
                    "completion_tokens": 7,
                    "prompt_tokens_details": {"cached_tokens": 10}
                  }
                }""";

        UsageTokens usage = parser.parseSSELine(sseData);

        assertEquals(20, usage.getInputTokens());
        assertEquals(7, usage.getOutputTokens());
        assertEquals(10, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("流式 choices 内 usage 也会被解析")
    void parseSSELineExtractsChoiceUsage() {
        String sseData = """
                {
                  "choices": [
                    {
                      "delta": {},
                      "usage": {
                        "prompt_tokens": 11,
                        "completion_tokens": 3
                      }
                    }
                  ]
                }""";

        UsageTokens usage = parser.parseSSELine(sseData);

        assertEquals(11, usage.getInputTokens());
        assertEquals(3, usage.getOutputTokens());
    }

    @Test
    @DisplayName("兼容 Responses 字段别名 input_tokens/output_tokens")
    void parseSSELineExtractsResponseStyleAliases() {
        String sseData = """
                {
                  "response": {
                    "usage": {
                      "input_tokens": 25,
                      "output_tokens": 9,
                      "input_tokens_details": {"cached_tokens": 5}
                    }
                  }
                }""";

        UsageTokens usage = parser.parseSSELine(sseData);

        assertEquals(20, usage.getInputTokens());
        assertEquals(9, usage.getOutputTokens());
        assertEquals(5, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("流式 chunk 无 usage 时返回 null")
    void parseSSELineReturnsNullWithoutUsage() {
        assertNull(parser.parseSSELine("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}"));
    }
}
