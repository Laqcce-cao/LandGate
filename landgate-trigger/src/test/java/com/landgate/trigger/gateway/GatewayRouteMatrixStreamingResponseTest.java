package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
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

    @Test
    @DisplayName("Messages 非流式聚合缺 terminal event 时返回 Anthropic 错误格式")
    void messagesNonStreamingAggregationMissingTerminalUsesAnthropicErrorEnvelope() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("messages_non_streaming_missing_terminal")
                .requestPlatform(Platform.ANTHROPIC)
                .requestFormat("messages")
                .requestedModel("gpt-5.5")
                .selectedAccount(account(Platform.OPENAI, AccountType.API_KEY))
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        "messages",
                        "chat_completions",
                        EndpointKind.OPENAI_CHAT_COMPLETIONS,
                        "https://upstream.example.com/v1/chat/completions",
                        true,
                        false,
                        "chat_completions",
                        "messages_non_streaming_missing_terminal"))
                .stream(true)
                .build());
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        GatewayResponseResult result = responseService.handleStreamingAsNonStreaming(
                new InputStreamHttpResponse("""
                        data: {"id":"chatcmpl_partial","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"partial"}}]}

                        """, "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new OpenAiUsageParser());

        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(servletResponse.getContentAsString());
        assertEquals(502, servletResponse.getStatus());
        assertEquals("error", body.get("type").asText());
        assertEquals("upstream_error", body.get("error").get("type").asText());
        assertEquals(GatewayStreamAggregationPolicy.MISSING_TERMINAL_MESSAGE,
                body.get("error").get("message").asText());
        assertFalse(result.usage().hasUsage());
    }

    @Test
    @DisplayName("Messages 非流式聚合 response.failed 时返回 Anthropic 错误格式")
    void messagesNonStreamingAggregationFailedTerminalUsesAnthropicErrorEnvelope() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("messages_non_streaming_failed_terminal")
                .requestPlatform(Platform.ANTHROPIC)
                .requestFormat("messages")
                .requestedModel("gpt-5.5")
                .selectedAccount(account(Platform.OPENAI, AccountType.OAUTH))
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        "messages",
                        "responses",
                        EndpointKind.OPENAI_CODEX_RESPONSES,
                        "https://upstream.example.com/backend-api/codex/responses",
                        true,
                        true,
                        "responses",
                        "messages_non_streaming_failed_terminal"))
                .stream(true)
                .build());
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        GatewayResponseResult result = responseService.handleStreamingAsNonStreaming(
                new InputStreamHttpResponse("""
                        data: {"type":"response.failed","response":{"id":"resp_failed","status":"failed","error":{"message":"upstream failed"}}}

                        """, "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new ResponsesUsageParser());

        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(servletResponse.getContentAsString());
        assertEquals(502, servletResponse.getStatus());
        assertEquals("error", body.get("type").asText());
        assertEquals("upstream_error", body.get("error").get("type").asText());
        assertEquals("upstream failed", body.get("error").get("message").asText());
        assertFalse(result.usage().hasUsage());
    }

    @Test
    @DisplayName("Anthropic 上游 SSE 可聚合为 Responses 非流式 JSON")
    void anthropicSseAggregatesToResponsesJsonForNonStreamingClient() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("anthropic_sse_to_responses_json")
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
                        "anthropic_sse_to_responses_json"))
                .stream(true)
                .build());
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        GatewayResponseResult result = responseService.handleStreamingAsNonStreaming(
                new InputStreamHttpResponse(upstreamSse("messages"), "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new AnthropicUsageParser());

        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(servletResponse.getContentAsString());
        assertEquals(200, servletResponse.getStatus());
        assertEquals("response", body.get("object").asText());
        assertEquals("Hello stream", body.get("output").get(0).get("content").get(0).get("text").asText());
        assertEquals(10, body.get("usage").get("input_tokens").asInt());
        assertEquals(2, body.get("usage").get("output_tokens").asInt());
        assertEquals(3, body.get("usage").get("input_tokens_details").get("cached_tokens").asInt());
        assertEquals(10, result.usage().getInputTokens());
        assertEquals(2, result.usage().getOutputTokens());
        assertEquals(3, result.usage().getCacheReadTokens());
    }

    @Test
    @DisplayName("Anthropic 上游 SSE 可聚合并翻译为 Chat 非流式 JSON")
    void anthropicSseAggregatesToChatJsonForNonStreamingClient() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("anthropic_sse_to_chat_json")
                .requestPlatform(Platform.OPENAI)
                .requestFormat("chat_completions")
                .requestedModel("claude-sonnet-4-5")
                .selectedAccount(account(Platform.ANTHROPIC, AccountType.API_KEY))
                .upstreamRoute(new UpstreamRoute(
                        Platform.ANTHROPIC,
                        "chat_completions",
                        "messages",
                        EndpointKind.ANTHROPIC_MESSAGES,
                        "https://upstream.example.com/v1/messages",
                        true,
                        false,
                        "messages",
                        "anthropic_sse_to_chat_json"))
                .stream(true)
                .build());
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        GatewayResponseResult result = responseService.handleStreamingAsNonStreaming(
                new InputStreamHttpResponse(upstreamSse("messages"), "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new AnthropicUsageParser());

        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(servletResponse.getContentAsString());
        assertEquals(200, servletResponse.getStatus());
        assertEquals("chat.completion", body.get("object").asText());
        assertEquals("Hello stream", body.get("choices").get(0).get("message").get("content").asText());
        assertEquals("stop", body.get("choices").get(0).get("finish_reason").asText());
        assertEquals(10, body.get("usage").get("prompt_tokens").asInt());
        assertEquals(2, body.get("usage").get("completion_tokens").asInt());
        assertEquals(3, body.get("usage").get("prompt_tokens_details").get("cached_tokens").asInt());
        assertEquals(10, result.usage().getInputTokens());
        assertEquals(2, result.usage().getOutputTokens());
        assertEquals(3, result.usage().getCacheReadTokens());
    }

    @Test
    @DisplayName("Chat 上游 SSE 可聚合为 Responses 非流式 JSON")
    void chatSseAggregatesToResponsesJsonForNonStreamingClient() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("chat_sse_to_responses_json")
                .requestPlatform(Platform.OPENAI)
                .requestFormat("responses")
                .requestedModel("gpt-5.5")
                .selectedAccount(account(Platform.OPENAI, AccountType.API_KEY))
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        "responses",
                        "chat_completions",
                        EndpointKind.OPENAI_CHAT_COMPLETIONS,
                        "https://upstream.example.com/v1/chat/completions",
                        true,
                        false,
                        "chat_completions",
                        "chat_sse_to_responses_json"))
                .stream(true)
                .build());
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        GatewayResponseResult result = responseService.handleStreamingAsNonStreaming(
                new InputStreamHttpResponse(upstreamSse("chat_completions"), "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new OpenAiUsageParser());

        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(servletResponse.getContentAsString());
        assertEquals(200, servletResponse.getStatus());
        assertEquals("response", body.get("object").asText());
        assertEquals("Hello stream", body.get("output").get(0).get("content").get(0).get("text").asText());
        assertEquals(10, body.get("usage").get("input_tokens").asInt());
        assertEquals(2, body.get("usage").get("output_tokens").asInt());
        assertEquals(3, body.get("usage").get("input_tokens_details").get("cached_tokens").asInt());
        assertEquals(7, result.usage().getInputTokens());
        assertEquals(2, result.usage().getOutputTokens());
        assertEquals(3, result.usage().getCacheReadTokens());
    }

    @Test
    @DisplayName("Chat 上游 SSE 可聚合并翻译为 Messages 非流式 JSON")
    void chatSseAggregatesToMessagesJsonForNonStreamingClient() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("chat_sse_to_messages_json")
                .requestPlatform(Platform.ANTHROPIC)
                .requestFormat("messages")
                .requestedModel("gpt-5.5")
                .selectedAccount(account(Platform.OPENAI, AccountType.API_KEY))
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        "messages",
                        "chat_completions",
                        EndpointKind.OPENAI_CHAT_COMPLETIONS,
                        "https://upstream.example.com/v1/chat/completions",
                        true,
                        false,
                        "chat_completions",
                        "chat_sse_to_messages_json"))
                .stream(true)
                .build());
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        GatewayResponseResult result = responseService.handleStreamingAsNonStreaming(
                new InputStreamHttpResponse(upstreamSse("chat_completions"), "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new OpenAiUsageParser());

        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(servletResponse.getContentAsString());
        assertEquals(200, servletResponse.getStatus());
        assertEquals("message", body.get("type").asText());
        assertEquals("assistant", body.get("role").asText());
        assertEquals("Hello stream", body.get("content").get(0).get("text").asText());
        assertEquals("end_turn", body.get("stop_reason").asText());
        assertEquals(7, body.get("usage").get("input_tokens").asInt());
        assertEquals(2, body.get("usage").get("output_tokens").asInt());
        assertEquals(3, body.get("usage").get("cache_read_input_tokens").asInt());
        assertEquals(7, result.usage().getInputTokens());
        assertEquals(2, result.usage().getOutputTokens());
        assertEquals(3, result.usage().getCacheReadTokens());
    }

    @Test
    @DisplayName("Responses 上游 SSE 工具调用可聚合并翻译为 Messages 非流式 tool_use")
    void responsesSseToolCallAggregatesToMessagesToolUseForNonStreamingClient() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("responses_sse_tool_call_to_messages_json")
                .requestPlatform(Platform.ANTHROPIC)
                .requestFormat("messages")
                .requestedModel("gpt-5.5")
                .selectedAccount(account(Platform.OPENAI, AccountType.OAUTH))
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        "messages",
                        "responses",
                        EndpointKind.OPENAI_CODEX_RESPONSES,
                        "https://upstream.example.com/backend-api/codex/responses",
                        true,
                        true,
                        "responses",
                        "responses_sse_tool_call_to_messages_json"))
                .stream(true)
                .build());
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        GatewayResponseResult result = responseService.handleStreamingAsNonStreaming(
                new InputStreamHttpResponse("""
                        data: {"type":"response.created","response":{"id":"resp_tool","model":"gpt-5.5"}}

                        data: {"type":"response.output_item.added","output_index":0,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"get_weather","status":"in_progress"}}

                        data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\\"city\\":"}

                        data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"\\"NYC\\"}"}

                        data: {"type":"response.function_call_arguments.done","output_index":0,"arguments":"{\\"city\\":\\"NYC\\"}"}

                        data: {"type":"response.completed","response":{"id":"resp_tool","model":"gpt-5.5","status":"completed","usage":{"input_tokens":10,"output_tokens":2,"input_tokens_details":{"cached_tokens":3}}}}

                        """, "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new ResponsesUsageParser());

        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(servletResponse.getContentAsString());
        JsonNode toolUse = body.get("content").get(0);
        assertEquals(200, servletResponse.getStatus());
        assertEquals("message", body.get("type").asText());
        assertEquals("tool_use", toolUse.get("type").asText());
        assertEquals("call_1", toolUse.get("id").asText());
        assertEquals("get_weather", toolUse.get("name").asText());
        assertEquals("NYC", toolUse.get("input").get("city").asText());
        assertEquals("tool_use", body.get("stop_reason").asText());
        assertEquals(7, body.get("usage").get("input_tokens").asInt());
        assertEquals(2, body.get("usage").get("output_tokens").asInt());
        assertEquals(3, body.get("usage").get("cache_read_input_tokens").asInt());
        assertEquals(7, result.usage().getInputTokens());
        assertEquals(2, result.usage().getOutputTokens());
        assertEquals(3, result.usage().getCacheReadTokens());
    }

    @Test
    @DisplayName("Chat 上游 SSE 工具调用可经 Responses IR 聚合并翻译为 Messages 非流式 tool_use")
    void chatSseToolCallAggregatesToMessagesToolUseForNonStreamingClient() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("chat_sse_tool_call_to_messages_json")
                .requestPlatform(Platform.ANTHROPIC)
                .requestFormat("messages")
                .requestedModel("gpt-5.5")
                .selectedAccount(account(Platform.OPENAI, AccountType.API_KEY))
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        "messages",
                        "chat_completions",
                        EndpointKind.OPENAI_CHAT_COMPLETIONS,
                        "https://upstream.example.com/v1/chat/completions",
                        true,
                        false,
                        "chat_completions",
                        "chat_sse_tool_call_to_messages_json"))
                .stream(true)
                .build());
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        GatewayResponseResult result = responseService.handleStreamingAsNonStreaming(
                new InputStreamHttpResponse("""
                        data: {"id":"chatcmpl_tool","object":"chat.completion.chunk","created":1,"model":"gpt-5.5","choices":[{"index":0,"delta":{"role":"assistant"}}]}

                        data: {"id":"chatcmpl_tool","object":"chat.completion.chunk","created":1,"model":"gpt-5.5","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather"}}]}}]}

                        data: {"id":"chatcmpl_tool","object":"chat.completion.chunk","created":1,"model":"gpt-5.5","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"city\\":"}}]}}]}

                        data: {"id":"chatcmpl_tool","object":"chat.completion.chunk","created":1,"model":"gpt-5.5","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"NYC\\"}"}}]}}]}

                        data: {"id":"chatcmpl_tool","object":"chat.completion.chunk","created":1,"model":"gpt-5.5","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":null}

                        data: {"id":"chatcmpl_tool","object":"chat.completion.chunk","created":1,"model":"gpt-5.5","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":3}}}

                        data: [DONE]

                        """, "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new OpenAiUsageParser());

        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(servletResponse.getContentAsString());
        JsonNode toolUse = body.get("content").get(0);
        assertEquals(200, servletResponse.getStatus());
        assertEquals("message", body.get("type").asText());
        assertEquals("tool_use", toolUse.get("type").asText());
        assertEquals("call_1", toolUse.get("id").asText());
        assertEquals("get_weather", toolUse.get("name").asText());
        assertEquals("NYC", toolUse.get("input").get("city").asText());
        assertEquals("tool_use", body.get("stop_reason").asText());
        assertEquals(7, body.get("usage").get("input_tokens").asInt());
        assertEquals(2, body.get("usage").get("output_tokens").asInt());
        assertEquals(3, body.get("usage").get("cache_read_input_tokens").asInt());
        assertEquals(7, result.usage().getInputTokens());
        assertEquals(2, result.usage().getOutputTokens());
        assertEquals(3, result.usage().getCacheReadTokens());
    }

    @Test
    @DisplayName("Anthropic 上游 SSE tool_use 可经 Responses IR 聚合为 Messages 非流式 tool_use")
    void anthropicSseToolUseAggregatesToMessagesToolUseForNonStreamingClient() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("anthropic_sse_tool_use_to_messages_json")
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
                        "anthropic_sse_tool_use_to_messages_json"))
                .stream(true)
                .build());
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        GatewayResponseResult result = responseService.handleStreamingAsNonStreaming(
                new InputStreamHttpResponse("""
                        data: {"type":"message_start","message":{"id":"msg_tool","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[],"usage":{"input_tokens":10,"cache_read_input_tokens":3}}}

                        data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{}}}

                        data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"city\\":"}}

                        data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\\"NYC\\"}"}}

                        data: {"type":"content_block_stop","index":0}

                        data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":2}}

                        data: {"type":"message_stop"}

                        """, "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new AnthropicUsageParser());

        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(servletResponse.getContentAsString());
        JsonNode toolUse = body.get("content").get(0);
        assertEquals(200, servletResponse.getStatus());
        assertEquals("message", body.get("type").asText());
        assertEquals("tool_use", toolUse.get("type").asText());
        assertEquals("toolu_1", toolUse.get("id").asText());
        assertEquals("get_weather", toolUse.get("name").asText());
        assertEquals("NYC", toolUse.get("input").get("city").asText());
        assertEquals("tool_use", body.get("stop_reason").asText());
        assertEquals(7, body.get("usage").get("input_tokens").asInt());
        assertEquals(2, body.get("usage").get("output_tokens").asInt());
        assertEquals(3, body.get("usage").get("cache_read_input_tokens").asInt());
        assertEquals(10, result.usage().getInputTokens());
        assertEquals(2, result.usage().getOutputTokens());
        assertEquals(3, result.usage().getCacheReadTokens());
    }

    @Test
    @DisplayName("Responses 上游 SSE web_search_call 可聚合并翻译为 Messages server tool blocks")
    void responsesSseWebSearchAggregatesToMessagesServerToolBlocksForNonStreamingClient() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("responses_sse_web_search_to_messages_json")
                .requestPlatform(Platform.ANTHROPIC)
                .requestFormat("messages")
                .requestedModel("gpt-5.5")
                .selectedAccount(account(Platform.OPENAI, AccountType.OAUTH))
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        "messages",
                        "responses",
                        EndpointKind.OPENAI_CODEX_RESPONSES,
                        "https://upstream.example.com/backend-api/codex/responses",
                        true,
                        true,
                        "responses",
                        "responses_sse_web_search_to_messages_json"))
                .stream(true)
                .build());
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        GatewayResponseResult result = responseService.handleStreamingAsNonStreaming(
                new InputStreamHttpResponse("""
                        data: {"type":"response.created","response":{"id":"resp_web_search","model":"gpt-5.5"}}

                        data: {"type":"response.output_item.done","output_index":0,"item":{"type":"web_search_call","id":"ws_1","status":"completed","action":{"type":"search","query":"LandGate"}}}

                        data: {"type":"response.output_item.added","output_index":1,"item":{"id":"msg_1","type":"message","status":"in_progress","role":"assistant","content":[]}}

                        data: {"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":"visible"}

                        data: {"type":"response.output_text.done","output_index":1,"content_index":0,"text":"visible"}

                        data: {"type":"response.completed","response":{"id":"resp_web_search","model":"gpt-5.5","status":"completed","usage":{"input_tokens":10,"output_tokens":2,"input_tokens_details":{"cached_tokens":3}}}}

                        """, "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new ResponsesUsageParser());

        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(servletResponse.getContentAsString());
        JsonNode content = body.get("content");
        assertEquals(200, servletResponse.getStatus());
        assertEquals("message", body.get("type").asText());
        assertEquals("server_tool_use", content.get(0).get("type").asText());
        assertEquals("srvtoolu_ws_1", content.get(0).get("id").asText());
        assertEquals("web_search", content.get(0).get("name").asText());
        assertEquals("LandGate", content.get(0).get("input").get("query").asText());
        assertEquals("web_search_tool_result", content.get(1).get("type").asText());
        assertEquals("srvtoolu_ws_1", content.get(1).get("tool_use_id").asText());
        assertEquals(0, content.get(1).get("content").size());
        assertEquals("text", content.get(2).get("type").asText());
        assertEquals("visible", content.get(2).get("text").asText());
        assertEquals("end_turn", body.get("stop_reason").asText());
        assertEquals(7, body.get("usage").get("input_tokens").asInt());
        assertEquals(2, body.get("usage").get("output_tokens").asInt());
        assertEquals(3, body.get("usage").get("cache_read_input_tokens").asInt());
        assertEquals(7, result.usage().getInputTokens());
        assertEquals(2, result.usage().getOutputTokens());
        assertEquals(3, result.usage().getCacheReadTokens());
    }

    @Test
    @DisplayName("Responses 上游 SSE hosted tool 输出聚合到 Messages 时不降级为可见 tool/text")
    void responsesSseHostedToolsAggregateToMessagesWithoutVisibleHostedPayload() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("responses_sse_hosted_tools_to_messages_json")
                .requestPlatform(Platform.ANTHROPIC)
                .requestFormat("messages")
                .requestedModel("gpt-5.5")
                .selectedAccount(account(Platform.OPENAI, AccountType.OAUTH))
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        "messages",
                        "responses",
                        EndpointKind.OPENAI_CODEX_RESPONSES,
                        "https://upstream.example.com/backend-api/codex/responses",
                        true,
                        true,
                        "responses",
                        "responses_sse_hosted_tools_to_messages_json"))
                .stream(true)
                .build());
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        GatewayResponseResult result = responseService.handleStreamingAsNonStreaming(
                new InputStreamHttpResponse("""
                        data: {"type":"response.created","response":{"id":"resp_hosted","model":"gpt-5.5"}}

                        data: {"type":"response.output_item.done","output_index":0,"item":{"type":"file_search_call","id":"fs_1","status":"completed"}}

                        data: {"type":"response.output_item.done","output_index":1,"item":{"type":"local_shell_call","id":"sh_1","status":"completed"}}

                        data: {"type":"response.output_item.added","output_index":2,"item":{"id":"msg_1","type":"message","status":"in_progress","role":"assistant","content":[]}}

                        data: {"type":"response.output_text.delta","output_index":2,"content_index":0,"delta":"visible answer"}

                        data: {"type":"response.output_text.done","output_index":2,"content_index":0,"text":"visible answer"}

                        data: {"type":"response.completed","response":{"id":"resp_hosted","model":"gpt-5.5","status":"completed","usage":{"input_tokens":10,"output_tokens":2,"input_tokens_details":{"cached_tokens":3}}}}

                        """, "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new ResponsesUsageParser());

        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(servletResponse.getContentAsString());
        JsonNode content = body.get("content");
        assertEquals(200, servletResponse.getStatus());
        assertEquals("message", body.get("type").asText());
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).get("type").asText());
        assertEquals("visible answer", content.get(0).get("text").asText());
        assertFalse(content.toString().contains("tool_use"));
        assertFalse(content.toString().contains("file_search_call"));
        assertFalse(content.toString().contains("local_shell_call"));
        assertEquals("end_turn", body.get("stop_reason").asText());
        assertEquals(7, body.get("usage").get("input_tokens").asInt());
        assertEquals(2, body.get("usage").get("output_tokens").asInt());
        assertEquals(3, body.get("usage").get("cache_read_input_tokens").asInt());
        assertEquals(7, result.usage().getInputTokens());
        assertEquals(2, result.usage().getOutputTokens());
        assertEquals(3, result.usage().getCacheReadTokens());
    }

    @Test
    @DisplayName("Responses 上游 SSE hosted tool 输出聚合到 Chat 时不降级为 tool_calls")
    void responsesSseHostedToolsAggregateToChatWithoutToolCalls() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("responses_sse_hosted_tools_to_chat_json")
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
                        "responses_sse_hosted_tools_to_chat_json"))
                .stream(true)
                .build());
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        GatewayResponseResult result = responseService.handleStreamingAsNonStreaming(
                new InputStreamHttpResponse("""
                        data: {"type":"response.created","response":{"id":"resp_hosted","model":"gpt-5.5"}}

                        data: {"type":"response.output_item.done","output_index":0,"item":{"type":"file_search_call","id":"fs_1","status":"completed"}}

                        data: {"type":"response.output_item.done","output_index":1,"item":{"type":"local_shell_call","id":"sh_1","status":"completed"}}

                        data: {"type":"response.output_item.added","output_index":2,"item":{"id":"msg_1","type":"message","status":"in_progress","role":"assistant","content":[]}}

                        data: {"type":"response.output_text.delta","output_index":2,"content_index":0,"delta":"visible answer"}

                        data: {"type":"response.output_text.done","output_index":2,"content_index":0,"text":"visible answer"}

                        data: {"type":"response.completed","response":{"id":"resp_hosted","model":"gpt-5.5","status":"completed","usage":{"input_tokens":10,"output_tokens":2,"input_tokens_details":{"cached_tokens":3}}}}

                        """, "text/event-stream"),
                servletResponse,
                GatewayRequestContext.get(),
                new ResponsesUsageParser());

        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(servletResponse.getContentAsString());
        JsonNode message = body.get("choices").get(0).get("message");
        assertEquals(200, servletResponse.getStatus());
        assertEquals("chat.completion", body.get("object").asText());
        assertEquals("visible answer", message.get("content").asText());
        assertFalse(message.has("tool_calls"));
        assertFalse(message.toString().contains("file_search_call"));
        assertFalse(message.toString().contains("local_shell_call"));
        assertEquals("stop", body.get("choices").get(0).get("finish_reason").asText());
        assertEquals(10, body.get("usage").get("prompt_tokens").asInt());
        assertEquals(2, body.get("usage").get("completion_tokens").asInt());
        assertEquals(3, body.get("usage").get("prompt_tokens_details").get("cached_tokens").asInt());
        assertEquals(7, result.usage().getInputTokens());
        assertEquals(2, result.usage().getOutputTokens());
        assertEquals(3, result.usage().getCacheReadTokens());
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
