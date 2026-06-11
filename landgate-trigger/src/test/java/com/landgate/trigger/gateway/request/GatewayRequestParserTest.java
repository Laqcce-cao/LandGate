package com.landgate.trigger.gateway.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GatewayRequestParser 测试")
class GatewayRequestParserTest {

    private final GatewayRequestParser parser = new GatewayRequestParser();

    @Test
    @DisplayName("Chat Completions 客户端 stream=false 时不要求流式")
    void chatCompletionsStreamFalseIsNotClientStreamingIntent() {
        String body = """
                {
                  "model":"gpt-5.5",
                  "stream":false,
                  "messages":[{"role":"user","content":"Hi"}]
                }""";

        assertFalse(GatewayRequestParser.shouldClientRequestStreaming("chat_completions", body));
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

        assertTrue(GatewayRequestParser.shouldClientRequestStreaming("chat_completions", body));
    }

    @Test
    @DisplayName("Responses 客户端格式默认按流式响应处理")
    void responsesRequestFormatDefaultsToStreaming() {
        String body = """
                {
                  "model":"gpt-5.5",
                  "input":"Hi"
                }""";

        assertTrue(GatewayRequestParser.shouldClientRequestStreaming("responses", body));
    }

    @Test
    @DisplayName("解析时优先使用 request attribute 中的 model 和 upstream path")
    void parsePrefersRequestAttributes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GatewayRequestParser.ATTR_GATEWAY_MODEL, "path-model");
        request.setAttribute(GatewayRequestParser.ATTR_GATEWAY_UPSTREAM_PATH,
                "/v1beta/models/path-model:generateContent");

        GatewayRequestInfo info = parser.parse("{\"model\":\"body-model\",\"stream\":true}",
                request, "chat_completions");

        assertEquals("path-model", info.model());
        assertEquals("/v1beta/models/path-model:generateContent", info.upstreamPath());
        assertTrue(info.clientStream());
    }
}
