package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.trigger.gateway.converter.AnthropicConverter;
import com.landgate.trigger.gateway.converter.ChatCompletionsConverter;
import com.landgate.trigger.gateway.converter.ConverterRegistry;
import com.landgate.trigger.gateway.converter.ProtocolTranslationService;
import com.landgate.trigger.gateway.converter.ResponsesConverter;
import com.landgate.trigger.gateway.oauth.AnthropicToolNameRewrite;
import com.landgate.trigger.gateway.response.GatewayResponseResult;
import com.landgate.trigger.gateway.response.GatewayResponseService;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.trigger.gateway.usage.AnthropicUsageParser;
import com.landgate.trigger.gateway.usage.IUsageParser;
import com.landgate.trigger.gateway.usage.OpenAiUsageParser;
import com.landgate.trigger.gateway.usage.ResponsesUsageParser;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.GatewayStreamAggregationPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Gateway route matrix streaming response tests")
class GatewayRouteMatrixStreamingResponseTest {

    private final ConverterRegistry converterRegistry = converterRegistry();
    private final ProtocolTranslationService translationService = new ProtocolTranslationService(converterRegistry);
    private final GatewayResponseService responseService = new GatewayResponseService(null, translationService, converterRegistry);

    @AfterEach
    void tearDown() {
        GatewayRequestContext.clear();
    }

    @Test
    @DisplayName("上游流式 SSE 响应按完整 12 条 route matrix 翻译回客户端 SSE 格式")
    void streamingResponsesFollowFullCoreRouteMatrix() throws Exception {
        List<StreamingMatrixCase> cases = List.of(
                matrix("messages <- Anthropic messages SSE", Platform.ANTHROPIC, AccountType.API_KEY,
                        Platform.ANTHROPIC, "messages", "messages", EndpointKind.ANTHROPIC_MESSAGES),
                matrix("responses <- Anthropic messages SSE", Platform.ANTHROPIC, AccountType.API_KEY,
                        Platform.OPENAI, "responses", "messages", EndpointKind.ANTHROPIC_MESSAGES),
                matrix("chat <- Anthropic messages SSE", Platform.ANTHROPIC, AccountType.API_KEY,
                        Platform.OPENAI, "chat_completions", "messages", EndpointKind.ANTHROPIC_MESSAGES),

                matrix("messages <- OpenAI OAuth Codex responses SSE", Platform.OPENAI, AccountType.OAUTH,
                        Platform.ANTHROPIC, "messages", "responses", EndpointKind.OPENAI_CODEX_RESPONSES),
                matrix("responses <- OpenAI OAuth Codex responses SSE", Platform.OPENAI, AccountType.OAUTH,
                        Platform.OPENAI, "responses", "responses", EndpointKind.OPENAI_CODEX_RESPONSES),
                matrix("chat <- OpenAI OAuth Codex responses SSE", Platform.OPENAI, AccountType.OAUTH,
                        Platform.OPENAI, "chat_completions", "responses", EndpointKind.OPENAI_CODEX_RESPONSES),

                matrix("messages <- OpenAI API Key responses SSE", Platform.OPENAI, AccountType.API_KEY,
                        Platform.ANTHROPIC, "messages", "responses", EndpointKind.OPENAI_RESPONSES),
                matrix("responses <- OpenAI API Key responses SSE", Platform.OPENAI, AccountType.API_KEY,
                        Platform.OPENAI, "responses", "responses", EndpointKind.OPENAI_RESPONSES),
                matrix("chat <- OpenAI API Key responses SSE", Platform.OPENAI, AccountType.API_KEY,
                        Platform.OPENAI, "chat_completions", "responses", EndpointKind.OPENAI_RESPONSES),

                matrix("messages <- OpenAI API Key chat SSE", Platform.OPENAI, AccountType.API_KEY,
                        Platform.ANTHROPIC, "messages", "chat_completions", EndpointKind.OPENAI_CHAT_COMPLETIONS),
                matrix("responses <- OpenAI API Key chat SSE", Platform.OPENAI, AccountType.API_KEY,
                        Platform.OPENAI, "responses", "chat_completions", EndpointKind.OPENAI_CHAT_COMPLETIONS),
                matrix("chat <- OpenAI API Key chat SSE", Platform.OPENAI, AccountType.API_KEY,
                        Platform.OPENAI, "chat_completions", "chat_completions", EndpointKind.OPENAI_CHAT_COMPLETIONS)
        );

        for (StreamingMatrixCase tc : cases) {
            MockHttpServletResponse servletResponse = new MockHttpServletResponse();
            GatewayRequestContext.set(GatewayRequestContext.builder()
                    .requestId("stream_matrix_" + tc.name().replace(' ', '_'))
                    .requestPlatform(tc.requestPlatform())
                    .requestFormat(tc.clientFormat())
                    .requestedModel("gpt-5.5")
                    .selectedAccount(account(tc.accountPlatform(), tc.accountType()))
                    .upstreamRoute(route(tc))
                    .stream(true)
                    .build());

            GatewayResponseResult result = responseService.handleStreaming(
                    new InputStreamHttpResponse(upstreamSse(tc.upstreamFormat()), "text/event-stream"),
                    servletResponse,
                    GatewayRequestContext.get(),
                    usageParser(tc.upstreamFormat()));

            assertEquals(200, servletResponse.getStatus(), tc.name());
            assertTrue(servletResponse.getContentType().startsWith("text/event-stream"), tc.name());
            String output = servletResponse.getContentAsString();
            assertClientSseShape(tc.clientFormat(), output, tc.name());
            assertSseFramesAreSeparated(output, tc.name());
            assertUsage(tc.upstreamFormat(), result.usage(), tc.name());
        }
    }

    @Test
    @DisplayName("客户端断开后继续 drain Responses 上游直到 terminal usage")
    void clientDisconnectDrainsUpstreamResponsesUsage() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("client_disconnect_drain_responses_usage")
                .requestPlatform(Platform.OPENAI)
                .requestFormat("chat_completions")
                .requestedModel("gpt-5.5")
                .selectedAccount(account(Platform.OPENAI, AccountType.OAUTH))
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        "chat_completions",
                        "responses",
                        EndpointKind.OPENAI_CODEX_RESPONSES,
                        "https://upstream.example.com/backend-api/codex/responses",
                        true,
                        true,
                        "responses",
                        "client_disconnect_drain_responses_usage"))
                .stream(true)
                .build());

        GatewayResponseResult result = responseService.handleStreaming(
                new InputStreamHttpResponse(upstreamSse("responses"), "text/event-stream"),
                new DisconnectingMockHttpServletResponse(),
                GatewayRequestContext.get(),
                new ResponsesUsageParser());

        assertTrue(result.clientDisconnected());
        assertEquals(7, result.usage().getInputTokens());
        assertEquals(2, result.usage().getOutputTokens());
        assertEquals(3, result.usage().getCacheReadTokens());
    }

    @Test
    @DisplayName("Anthropic OAuth mimicry 流式响应在 usage 和翻译前还原工具名")
    void anthropicOAuthMimicryRestoresToolNamesBeforeStreamingResponseHandling() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("restore_tool_names_stream")
                .requestPlatform(Platform.ANTHROPIC)
                .requestFormat("messages")
                .requestedModel("claude-sonnet-4-5")
                .selectedAccount(account(Platform.ANTHROPIC, AccountType.OAUTH))
                .upstreamRoute(new UpstreamRoute(
                        Platform.ANTHROPIC,
                        "messages",
                        "messages",
                        EndpointKind.ANTHROPIC_MESSAGES,
                        "https://upstream.example.com/v1/messages",
                        true,
                        false,
                        "messages",
                        "anthropic_oauth_tool_restore_stream"))
                .stream(true)
                .shouldMimicClaudeCode(true)
                .anthropicToolNameRewrite(AnthropicToolNameRewrite.fromToolNames(List.of("sessions_read")))
                .build());

        String upstreamSse = """
                data: {"type":"message_start","message":{"id":"msg_stream","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[],"usage":{"input_tokens":10}}}

                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"cc_sess_read","input":{}}}

                data: {"type":"content_block_stop","index":0}

                data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":2}}

                data: {"type":"message_stop"}

                """;
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        GatewayResponseResult result = responseService.handleStreaming(
                new InputStreamHttpResponse(upstreamSse, "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new AnthropicUsageParser());

        String output = servletResponse.getContentAsString();
        assertTrue(output.contains("\"name\":\"sessions_read\""));
        assertFalse(output.contains("cc_sess_read"));
        assertEquals(10, result.usage().getInputTokens());
        assertEquals(2, result.usage().getOutputTokens());
    }

    @Test
    @DisplayName("Anthropic SSE cached_tokens compatibility is normalized before Responses stream translation")
    void anthropicSseCachedTokensCompatibilityBeforeResponsesTranslation() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("anthropic_stream_cached_tokens_to_responses")
                .requestPlatform(Platform.OPENAI)
                .requestFormat("responses")
                .requestedModel("claude-sonnet-4-5")
                .selectedAccount(account(Platform.ANTHROPIC, AccountType.API_KEY))
                .upstreamRoute(new UpstreamRoute(
                        Platform.ANTHROPIC,
                        "responses",
                        "messages",
                        EndpointKind.ANTHROPIC_MESSAGES,
                        "https://upstream.example.com/v1/messages",
                        true,
                        false,
                        "messages",
                        "anthropic_stream_cached_tokens_compat"))
                .stream(true)
                .build());

        String upstreamSse = """
                data: {"type":"message_start","message":{"id":"msg_stream","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[],"usage":{"input_tokens":10,"cached_tokens":9,"cache_creation":{"ephemeral_5m_input_tokens":3,"ephemeral_1h_input_tokens":5}}}}

                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"cached stream"}}

                data: {"type":"content_block_stop","index":0}

                data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":2}}

                data: {"type":"message_stop"}

                """;
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        GatewayResponseResult result = responseService.handleStreaming(
                new InputStreamHttpResponse(upstreamSse, "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new AnthropicUsageParser());

        String output = servletResponse.getContentAsString();
        assertTrue(output.contains("\"cached_tokens\":9"));
        assertTrue(output.contains("\"input_tokens\":10"));
        assertTrue(output.contains("response.completed"));
        assertEquals(9, result.usage().getCacheReadTokens());
        assertEquals(8, result.usage().getCacheCreationTokens());
    }

    @Test
    @DisplayName("Anthropic Messages SSE passthrough keeps cached_tokens body unchanged while billing parser remains compatible")
    void anthropicSseCachedTokensPassthroughBodyIsNotNormalized() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("anthropic_stream_cached_tokens_passthrough")
                .requestPlatform(Platform.ANTHROPIC)
                .requestFormat("messages")
                .requestedModel("claude-sonnet-4-5")
                .selectedAccount(account(Platform.ANTHROPIC, AccountType.API_KEY))
                .upstreamRoute(new UpstreamRoute(
                        Platform.ANTHROPIC,
                        "messages",
                        "messages",
                        EndpointKind.ANTHROPIC_MESSAGES,
                        "https://upstream.example.com/v1/messages",
                        true,
                        false,
                        "messages",
                        "anthropic_stream_cached_tokens_passthrough"))
                .stream(true)
                .build());

        String upstreamSse = """
                data: {"type":"message_start","message":{"id":"msg_stream","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[],"usage":{"input_tokens":10,"cached_tokens":9,"cache_creation":{"ephemeral_5m_input_tokens":3,"ephemeral_1h_input_tokens":5}}}}

                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"cached stream"}}

                data: {"type":"content_block_stop","index":0}

                data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":2}}

                data: {"type":"message_stop"}

                """;
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        GatewayResponseResult result = responseService.handleStreaming(
                new InputStreamHttpResponse(upstreamSse, "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new AnthropicUsageParser());

        String output = servletResponse.getContentAsString();
        assertTrue(output.contains("\"cached_tokens\":9"));
        assertFalse(output.contains("cache_read_input_tokens"));
        assertFalse(output.contains("cache_creation_input_tokens"));
        assertEquals(9, result.usage().getCacheReadTokens());
        assertEquals(8, result.usage().getCacheCreationTokens());
    }

    @Test
    @DisplayName("Anthropic Messages 流式响应缺少 terminal event 时标记协议错误")
    void anthropicMessagesStreamMissingMessageStopReturnsProtocolError() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("anthropic_stream_missing_terminal")
                .requestPlatform(Platform.ANTHROPIC)
                .requestFormat("messages")
                .requestedModel("claude-sonnet-4-5")
                .selectedAccount(account(Platform.ANTHROPIC, AccountType.API_KEY))
                .upstreamRoute(new UpstreamRoute(
                        Platform.ANTHROPIC,
                        "messages",
                        "messages",
                        EndpointKind.ANTHROPIC_MESSAGES,
                        "https://upstream.example.com/v1/messages",
                        true,
                        false,
                        "messages",
                        "anthropic_stream_missing_terminal"))
                .stream(true)
                .build());

        String upstreamSse = """
                data: {"type":"message_start","message":{"usage":{"input_tokens":11}}}

                data: {"type":"message_delta","usage":{"output_tokens":5}}

                """;
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        GatewayResponseResult result = responseService.handleStreaming(
                new InputStreamHttpResponse(upstreamSse, "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new AnthropicUsageParser());

        assertEquals(200, servletResponse.getStatus());
        assertFalse(servletResponse.getContentAsString().contains("data: [DONE]"));
        assertTrue(result.protocolError());
        assertEquals(GatewayStreamAggregationPolicy.MISSING_TERMINAL_MESSAGE,
                result.protocolErrorMessage());
        assertEquals(11, result.usage().getInputTokens());
        assertEquals(5, result.usage().getOutputTokens());
    }

    @Test
    @DisplayName("Anthropic Messages passthrough 兼容 [DONE] terminal sentinel")
    void anthropicMessagesDoneSentinelCountsAsTerminal() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("anthropic_stream_done_terminal")
                .requestPlatform(Platform.ANTHROPIC)
                .requestFormat("messages")
                .requestedModel("claude-sonnet-4-5")
                .selectedAccount(account(Platform.ANTHROPIC, AccountType.API_KEY))
                .upstreamRoute(new UpstreamRoute(
                        Platform.ANTHROPIC,
                        "messages",
                        "messages",
                        EndpointKind.ANTHROPIC_MESSAGES,
                        "https://upstream.example.com/v1/messages",
                        true,
                        false,
                        "messages",
                        "anthropic_stream_done_terminal"))
                .stream(true)
                .build());

        String upstreamSse = """
                data: {"type":"message_start","message":{"usage":{"input_tokens":11}}}

                data: {"type":"message_delta","usage":{"output_tokens":5}}

                data: [DONE]

                """;

        GatewayResponseResult result = responseService.handleStreaming(
                new InputStreamHttpResponse(upstreamSse, "text/event-stream"),
                new MockHttpServletResponse(),
                GatewayRequestContext.get(),
                new AnthropicUsageParser());

        assertFalse(result.protocolError());
        assertEquals(11, result.usage().getInputTokens());
        assertEquals(5, result.usage().getOutputTokens());
    }

    @Test
    @DisplayName("Responses 翻译到 Chat 时仅有 [DONE] 不算 Responses terminal")
    void responsesToChatDoneSentinelWithoutResponsesTerminalReturnsProtocolError() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("responses_to_chat_done_without_terminal")
                .requestPlatform(Platform.OPENAI)
                .requestFormat("chat_completions")
                .requestedModel("gpt-5.5")
                .selectedAccount(account(Platform.OPENAI, AccountType.OAUTH))
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        "chat_completions",
                        "responses",
                        EndpointKind.OPENAI_CODEX_RESPONSES,
                        "https://upstream.example.com/backend-api/codex/responses",
                        true,
                        true,
                        "responses",
                        "responses_to_chat_done_without_terminal"))
                .stream(true)
                .build());

        GatewayResponseResult result = responseService.handleStreaming(
                new InputStreamHttpResponse("data: [DONE]\n\n", "text/event-stream"),
                new MockHttpServletResponse(),
                GatewayRequestContext.get(),
                new ResponsesUsageParser());

        assertTrue(result.protocolError());
        assertEquals(GatewayStreamAggregationPolicy.MISSING_TERMINAL_MESSAGE,
                result.protocolErrorMessage());
        assertFalse(result.usage().hasUsage());
    }

    @Test
    @DisplayName("Responses raw passthrough 保留 [DONE] terminal 兼容")
    void responsesPassthroughDoneSentinelCountsAsTerminal() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("responses_passthrough_done_terminal")
                .requestPlatform(Platform.OPENAI)
                .requestFormat("responses")
                .requestedModel("gpt-5.5")
                .selectedAccount(account(Platform.OPENAI, AccountType.API_KEY))
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        "responses",
                        "responses",
                        EndpointKind.OPENAI_RESPONSES,
                        "https://upstream.example.com/v1/responses",
                        true,
                        false,
                        "responses",
                        "responses_passthrough_done_terminal"))
                .stream(true)
                .build());

        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        GatewayResponseResult result = responseService.handleStreaming(
                new InputStreamHttpResponse("data: [DONE]\n\n", "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new ResponsesUsageParser());

        assertFalse(result.protocolError());
        assertTrue(servletResponse.getContentAsString().contains("data: [DONE]"));
    }

    private static void assertClientSseShape(String clientFormat, String output, String name) {
        assertTrue(output.contains("Hello stream"), name);
        switch (clientFormat) {
            case "messages" -> {
                assertTrue(output.contains("message_start"), name);
                assertTrue(output.contains("message_delta") || output.contains("content_block_delta"), name);
                assertTrue(output.contains("message_stop"), name);
            }
            case "responses" -> {
                assertTrue(output.contains("response.output_text.delta"), name);
                assertTrue(output.contains("response.completed"), name);
            }
            case "chat_completions" -> {
                assertTrue(output.contains("chat.completion.chunk"), name);
                assertTrue(output.contains("data: [DONE]"), name);
            }
            default -> fail("unknown client format " + clientFormat);
        }
    }

    private static void assertSseFramesAreSeparated(String output, String name) {
        String[] lines = output.split("\n", -1);
        boolean sawDataLine = false;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("data:")) {
                continue;
            }
            sawDataLine = true;
            assertTrue(i + 1 < lines.length, name + " data line must be followed by an SSE frame separator");
            assertTrue(lines[i + 1].trim().isEmpty(),
                    name + " data line must be followed by an empty SSE frame separator line: " + lines[i]);
        }
        assertTrue(sawDataLine, name + " should emit SSE data lines");
    }

    private static void assertUsage(String upstreamFormat, UsageTokens usage, String name) {
        assertNotNull(usage, name);
        switch (upstreamFormat) {
            case "messages" -> {
                assertEquals(10, usage.getInputTokens(), name);
                assertEquals(2, usage.getOutputTokens(), name);
                assertEquals(3, usage.getCacheReadTokens(), name);
            }
            case "responses", "chat_completions" -> {
                assertEquals(7, usage.getInputTokens(), name);
                assertEquals(2, usage.getOutputTokens(), name);
                assertEquals(3, usage.getCacheReadTokens(), name);
            }
            default -> fail("unknown upstream format " + upstreamFormat);
        }
    }

    private static String upstreamSse(String upstreamFormat) {
        return switch (upstreamFormat) {
            case "messages" -> """
                    data: {"type":"message_start","message":{"id":"msg_stream","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[],"usage":{"input_tokens":10,"cache_read_input_tokens":3}}}

                    data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello stream"}}

                    data: {"type":"content_block_stop","index":0}

                    data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":2}}

                    data: {"type":"message_stop"}

                    """;
            case "responses" -> """
                    data: {"type":"response.created","response":{"id":"resp_stream","model":"gpt-5.5"}}

                    data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_1","type":"message","status":"in_progress","role":"assistant","content":[]}}

                    data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Hello stream"}

                    data: {"type":"response.output_text.done","output_index":0,"content_index":0,"text":"Hello stream"}

                    data: {"type":"response.completed","response":{"id":"resp_stream","model":"gpt-5.5","status":"completed","usage":{"input_tokens":10,"output_tokens":2,"input_tokens_details":{"cached_tokens":3}}}}

                    """;
            case "chat_completions" -> """
                    data: {"id":"chatcmpl_stream","object":"chat.completion.chunk","created":1,"model":"gpt-5.5","choices":[{"index":0,"delta":{"role":"assistant"}}]}

                    data: {"id":"chatcmpl_stream","object":"chat.completion.chunk","created":1,"model":"gpt-5.5","choices":[{"index":0,"delta":{"content":"Hello stream"}}]}

                    data: {"id":"chatcmpl_stream","object":"chat.completion.chunk","created":1,"model":"gpt-5.5","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":null}

                    data: {"id":"chatcmpl_stream","object":"chat.completion.chunk","created":1,"model":"gpt-5.5","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":3}}}

                    data: [DONE]

                    """;
            default -> throw new IllegalArgumentException("unknown upstream format " + upstreamFormat);
        };
    }

    private static IUsageParser usageParser(String upstreamFormat) {
        return switch (upstreamFormat) {
            case "messages" -> new AnthropicUsageParser();
            case "responses" -> new ResponsesUsageParser();
            case "chat_completions" -> new OpenAiUsageParser();
            default -> throw new IllegalArgumentException("unknown upstream format " + upstreamFormat);
        };
    }

    private static UpstreamRoute route(StreamingMatrixCase tc) {
        return new UpstreamRoute(
                tc.accountPlatform(),
                tc.clientFormat(),
                tc.upstreamFormat(),
                tc.endpointKind(),
                "https://upstream.example.com",
                true,
                tc.endpointKind() == EndpointKind.OPENAI_CODEX_RESPONSES,
                tc.upstreamFormat(),
                tc.name());
    }

    private static AccountEntity account(Platform platform, AccountType type) {
        return AccountEntity.builder()
                .id(1L)
                .platform(platform)
                .type(type)
                .name(platform.name().toLowerCase() + "-" + type.name().toLowerCase())
                .build();
    }

    private static StreamingMatrixCase matrix(String name,
                                              Platform accountPlatform,
                                              AccountType accountType,
                                              Platform requestPlatform,
                                              String clientFormat,
                                              String upstreamFormat,
                                              EndpointKind endpointKind) {
        return new StreamingMatrixCase(name, accountPlatform, accountType,
                requestPlatform, clientFormat, upstreamFormat, endpointKind);
    }

    private static ConverterRegistry converterRegistry() {
        ConverterRegistry registry = new ConverterRegistry();
        registry.register(List.of(
                new AnthropicConverter(),
                new ResponsesConverter(),
                new ChatCompletionsConverter()));
        return registry;
    }

    private record StreamingMatrixCase(
            String name,
            Platform accountPlatform,
            AccountType accountType,
            Platform requestPlatform,
            String clientFormat,
            String upstreamFormat,
            EndpointKind endpointKind
    ) {
    }

    private static class DisconnectingMockHttpServletResponse extends MockHttpServletResponse {
        private final PrintWriter writer = new PrintWriter(new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) throws IOException {
                throw new IOException("client disconnected");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        @Override
        public PrintWriter getWriter() {
            return writer;
        }
    }

    private static class InputStreamHttpResponse implements HttpResponse<InputStream> {
        private final int status;
        private final String body;
        private final Map<String, List<String>> headers;

        InputStreamHttpResponse(String body, String contentType) {
            this.status = 200;
            this.body = body;
            this.headers = Map.of("content-type", List.of(contentType));
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(headers, (k, v) -> true);
        }

        @Override
        public InputStream body() {
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("https://upstream.example.com");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
