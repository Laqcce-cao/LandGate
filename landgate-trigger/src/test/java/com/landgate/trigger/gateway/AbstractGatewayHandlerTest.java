package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.trigger.gateway.converter.AnthropicConverter;
import com.landgate.trigger.gateway.converter.ConverterRegistry;
import com.landgate.trigger.gateway.converter.ResponsesConverter;
import com.landgate.trigger.gateway.request.GatewayRequestParser;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.trigger.gateway.usage.IUsageParser;
import com.landgate.trigger.gateway.usage.ResponsesUsageParser;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AbstractGatewayHandler 单元测试 —— 验证网关通用请求决策逻辑。
 */
@DisplayName("AbstractGatewayHandler 测试")
class AbstractGatewayHandlerTest {

    @AfterEach
    void tearDown() {
        GatewayRequestContext.clear();
    }

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
    @DisplayName("流式翻译路径保留 Responses cached_tokens 用量")
    void translatedStreamingResponseKeepsResponsesCachedTokens() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("test-translation-cache")
                .requestPlatform(Platform.ANTHROPIC)
                .requestFormat("messages")
                .requestedModel("gpt-5.5")
                .selectedAccount(AccountEntity.builder().id(1L).name("openai-oauth").platform(Platform.OPENAI).build())
                .stream(true)
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        "messages",
                        "responses",
                        EndpointKind.OPENAI_CODEX_RESPONSES,
                        "https://chatgpt.com/backend-api/codex/responses",
                        false,
                        true,
                        true,
                        "responses",
                        "openai_oauth_codex"))
                .build());
        String sse = """
                data: {"type":"response.created","response":{"id":"resp_1","model":"gpt-5.5"}}

                data: {"type":"response.output_text.delta","delta":"hello"}

                data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":100,"output_tokens":5,"input_tokens_details":{"cached_tokens":80}}}}

                """;

        UsageTokens usage = handler.captureStreamingUsage(sse, new ResponsesUsageParser());

        assertEquals(20, usage.getInputTokens());
        assertEquals(5, usage.getOutputTokens());
        assertEquals(80, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("上游返回 text/event-stream 时按流式读取")
    void upstreamEventStreamResponseForcesStreamingHandling() {
        var upstreamResp = new InputStreamHttpResponse("data: {}\n", "text/event-stream;charset=UTF-8");

        assertTrue(AbstractGatewayHandler.shouldHandleResponseAsStreaming(false, upstreamResp));
    }

    @Test
    @DisplayName("OpenAI OAuth 上游流式可聚合为客户端非流式 messages 响应")
    void forcedUpstreamStreamingCanAggregateToNonStreamingMessages() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("test-stream-to-json")
                .requestPlatform(Platform.ANTHROPIC)
                .requestFormat("messages")
                .requestedModel("gpt-5.5")
                .selectedAccount(AccountEntity.builder().id(1L).name("openai-oauth").platform(Platform.OPENAI).build())
                .stream(true)
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        "messages",
                        "responses",
                        EndpointKind.OPENAI_CODEX_RESPONSES,
                        "https://chatgpt.com/backend-api/codex/responses",
                        false,
                        true,
                        true,
                        "responses",
                        "openai_oauth_codex"))
                .build());
        String sse = """
                data: {"type":"response.created","response":{"id":"resp_1","model":"gpt-5.5"}}

                data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_1","type":"message","status":"in_progress","role":"assistant","content":[]}}

                data: {"type":"response.output_text.delta","output_index":0,"delta":"non"}

                data: {"type":"response.output_text.delta","output_index":0,"delta":" stream ok"}

                data: {"type":"response.output_text.done","output_index":0}

                data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-5.5","status":"completed","usage":{"input_tokens":100,"output_tokens":6,"input_tokens_details":{"cached_tokens":80}}}}

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        UsageTokens usage = handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getContentAsString().contains("\"type\":\"message\""));
        assertTrue(response.getContentAsString().contains("non stream ok"));
        assertEquals(20, usage.getInputTokens());
        assertEquals(6, usage.getOutputTokens());
        assertEquals(80, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("流式透传路径继续解析 Responses cached_tokens 用量")
    void passthroughStreamingResponseKeepsResponsesCachedTokens() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("test-passthrough-cache")
                .requestPlatform(Platform.OPENAI)
                .requestFormat("responses")
                .requestedModel("gpt-5.5")
                .selectedAccount(AccountEntity.builder().id(1L).name("openai-oauth").platform(Platform.OPENAI).build())
                .stream(true)
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        "responses",
                        "responses",
                        EndpointKind.OPENAI_CODEX_RESPONSES,
                        "https://chatgpt.com/backend-api/codex/responses",
                        false,
                        true,
                        true,
                        "responses",
                        "openai_oauth_codex"))
                .build());
        String sse = """
                data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":100,"output_tokens":5,"input_tokens_details":{"cached_tokens":80}}}}

                """;

        UsageTokens usage = handler.captureStreamingUsage(sse, new ResponsesUsageParser());

        assertEquals(20, usage.getInputTokens());
        assertEquals(5, usage.getOutputTokens());
        assertEquals(80, usage.getCacheReadTokens());
    }

    private static ConverterRegistry converterRegistry() {
        ConverterRegistry registry = new ConverterRegistry();
        registry.register(List.of(new ResponsesConverter(), new AnthropicConverter()));
        return registry;
    }

    private static class TestGatewayHandler extends AbstractGatewayHandler {

        TestGatewayHandler(ConverterRegistry converterRegistry) {
            super(null, null, null, null, null, null, null, null, null, null,
                    null, converterRegistry, null, null, null, null, null, null, null, null, null);
        }

        UsageTokens captureStreamingUsage(String sse, IUsageParser usageParser) throws IOException {
            return handleStreaming(new InputStreamHttpResponse(sse, "text/event-stream"), new MockHttpServletResponse(),
                    GatewayRequestContext.get(), usageParser).usage();
        }

        UsageTokens captureStreamingAsNonStreaming(String sse, MockHttpServletResponse response,
                                                   IUsageParser usageParser) throws IOException {
            return handleStreamingAsNonStreaming(new InputStreamHttpResponse(sse, "text/event-stream"), response,
                    GatewayRequestContext.get(), usageParser);
        }

        @Override
        protected IErrorWriter getErrorWriter() {
            return null;
        }
    }

    private static class InputStreamHttpResponse implements HttpResponse<InputStream> {
        private final String body;
        private final String contentType;

        InputStreamHttpResponse(String body, String contentType) {
            this.body = body;
            this.contentType = contentType;
        }

        @Override public int statusCode() { return 200; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of("content-type", List.of(contentType)), (k, v) -> true);
        }
        @Override public InputStream body() { return new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://example.com"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

}
