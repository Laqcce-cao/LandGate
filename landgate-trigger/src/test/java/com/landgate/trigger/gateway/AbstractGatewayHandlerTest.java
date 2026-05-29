package com.landgate.trigger.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AbstractGatewayHandler 单元测试 —— 验证网关通用请求决策逻辑。
 */
@DisplayName("AbstractGatewayHandler 测试")
class AbstractGatewayHandlerTest {

    @Test
    @DisplayName("Chat Completions 客户端 stream=false 时不要求流式")
    void chatCompletionsStreamFalseIsNotClientStreamingIntent() {
        String body = """
                {
                  "model":"gpt-5.5",
                  "stream":false,
                  "messages":[{"role":"user","content":"Hi"}]
                }""";

        assertFalse(AbstractGatewayHandler.shouldClientRequestStreaming("chat_completions", body));
    }

    @Test
    @DisplayName("Chat Completions 客户端 stream=true 时要求流式")
    void chatCompletionsStreamTrueIsClientStreamingIntent() {
        String body = """
                {
                  "model":"gpt-5.5",
                  "stream":true,
                  "messages":[{"role":"user","content":"Hi"}]
                }""";

        assertTrue(AbstractGatewayHandler.shouldClientRequestStreaming("chat_completions", body));
    }

    @Test
    @DisplayName("Responses 客户端格式默认按流式响应处理")
    void responsesRequestFormatDefaultsToStreaming() {
        String body = """
                {
                  "model":"gpt-5.5",
                  "input":"Hi"
                }""";

        assertTrue(AbstractGatewayHandler.shouldClientRequestStreaming("responses", body));
    }
}
