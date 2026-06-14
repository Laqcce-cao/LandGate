package com.landgate.trigger.gateway;

import com.landgate.trigger.gateway.usage.OpenAiUsageParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenAiUsageParser 测试")
class OpenAiUsageParserTest {

    private final OpenAiUsageParser parser = new OpenAiUsageParser();

    @Test
    @DisplayName("流式结束识别兼容 data 冒号后的空格和 tab")
    void isStreamDoneAcceptsTolerantDataLineForms() {
        assertTrue(parser.isStreamDone("data: [DONE]"));
        assertTrue(parser.isStreamDone("data:[DONE]"));
        assertTrue(parser.isStreamDone("data:\t[DONE]"));
        assertFalse(parser.isStreamDone("data: {\"choices\":[]}"));
        assertFalse(parser.isStreamDone("event: done"));
    }
}
