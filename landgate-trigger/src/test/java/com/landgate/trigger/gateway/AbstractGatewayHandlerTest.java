package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.trigger.gateway.converter.AnthropicConverter;
import com.landgate.trigger.gateway.converter.ChatCompletionsConverter;
import com.landgate.trigger.gateway.converter.ConverterRegistry;
import com.landgate.trigger.gateway.converter.ResponsesConverter;
import com.landgate.trigger.gateway.error.IErrorWriter;
import com.landgate.trigger.gateway.AbstractGatewayHandler;
import com.landgate.trigger.gateway.GatewayRequestContext;
import com.landgate.trigger.gateway.converter.ProtocolTranslationService;
import com.landgate.trigger.gateway.request.GatewayRequestParser;
import com.landgate.trigger.gateway.response.GatewayResponseResult;
import com.landgate.trigger.gateway.retry.AnthropicThinkingRetryPolicy;
import com.landgate.trigger.gateway.session.OpenAiCompatSessionService;
import com.landgate.trigger.gateway.session.OpenAiCompatSessionStateBinder;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.trigger.gateway.usage.IUsageParser;
import com.landgate.trigger.gateway.usage.ResponsesUsageParser;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMapCache;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
    @DisplayName("Responses 客户端格式默认非流式")
    void responsesRequestFormatDefaultsToNonStreaming() {
        String body = """
                {
                  "model":"gpt-5.5",
                  "input":"Hi"
                }""";

        assertFalse(GatewayRequestParser.shouldClientRequestStreaming("responses", body));
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
    @DisplayName("上游 stream 意图为 true 时即使 Content-Type 是 JSON 也按流式读取")
    void upstreamStreamIntentForcesStreamingHandling() {
        var upstreamResp = new InputStreamHttpResponse("{}", "application/json");

        assertTrue(AbstractGatewayHandler.shouldHandleResponseAsStreaming(true, upstreamResp));
    }

    @Test
    @DisplayName("上游非 SSE JSON 且无 stream 意图时按非流式读取")
    void jsonResponseWithoutStreamIntentUsesNonStreamingHandling() {
        var upstreamResp = new InputStreamHttpResponse("{}", "application/json");

        assertFalse(AbstractGatewayHandler.shouldHandleResponseAsStreaming(false, upstreamResp));
    }

    @Test
    @DisplayName("OpenAI OAuth 上游流式可聚合为客户端非流式 messages 响应")
    void forcedUpstreamStreamingCanAggregateToNonStreamingMessages() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.ANTHROPIC, "messages"));
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
    @DisplayName("OpenAI API Key Messages compat 上游流式可聚合为客户端非流式 messages 响应")
    void apiKeyMessagesCompatForcedUpstreamStreamingAggregatesToNonStreamingMessages() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(openAiApiKeyResponsesContext(Platform.ANTHROPIC, "messages"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        UsageTokens usage = handler.captureStreamingAsNonStreaming(textOnlyResponsesSse(), response,
                new ResponsesUsageParser());

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getContentAsString().contains("\"type\":\"message\""));
        assertTrue(response.getContentAsString().contains("non stream ok"));
        assertEquals(20, usage.getInputTokens());
        assertEquals(6, usage.getOutputTokens());
        assertEquals(80, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("OpenAI API Key Messages compat 即使客户端断开也绑定 response id")
    void apiKeyMessagesCompatBindsResponseIdAfterClientDisconnect() throws Exception {
        OpenAiCompatSessionService sessionService = newOpenAiCompatSessionService();
        AccountEntity account = AccountEntity.builder()
                .id(7L)
                .name("openai-api-key")
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .build();
        GatewayRequestContext ctx = openAiApiKeyResponsesContext(Platform.ANTHROPIC, "messages");
        ctx.setSelectedAccount(account);
        ctx.setOpenAiCompatPromptCacheKey("stable-cache-key");

        OpenAiCompatSessionStateBinder.bindSuccess(
                sessionService,
                ctx,
                account,
                42L,
                new InputStreamHttpResponse("", "text/event-stream"),
                new GatewayResponseResult(UsageTokens.builder().build(), true, "resp_disconnected"));

        assertEquals("resp_disconnected",
                sessionService.getResponseId(account, 42L, "stable-cache-key"));
    }

    @Test
    @DisplayName("messages 客户端非流式聚合可从空 terminal output 的 Responses deltas 重建文本")
    void messagesClientAggregatesEmptyTerminalOutputFromBufferedResponsesDeltas() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.ANTHROPIC, "messages"));
        String sse = """
                data: {"type":"response.created","response":{"id":"resp_empty_output","model":"gpt-5.5"}}

                data: {"type":"response.output_text.delta","output_index":0,"delta":"rebuilt "}

                data: {"type":"response.output_text.delta","output_index":0,"delta":"answer"}

                data: {"type":"response.completed","response":{"id":"resp_empty_output","model":"gpt-5.5","status":"completed","output":[],"usage":{"input_tokens":15,"output_tokens":4,"input_tokens_details":{"cached_tokens":5}}}}

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        UsageTokens usage = handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        String body = response.getContentAsString();
        assertTrue(body.contains("\"type\":\"message\""));
        assertTrue(body.contains("\"text\":\"rebuilt answer\""));
        assertEquals(10, usage.getInputTokens());
        assertEquals(4, usage.getOutputTokens());
        assertEquals(5, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("OpenAI OAuth 上游流式可聚合为客户端非流式 chat.completion 响应")
    void forcedUpstreamStreamingCanAggregateToNonStreamingChat() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "chat_completions"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        UsageTokens usage = handler.captureStreamingAsNonStreaming(textOnlyResponsesSse(), response, new ResponsesUsageParser());

        assertTrue(response.getContentAsString().contains("\"object\":\"chat.completion\""));
        assertTrue(response.getContentAsString().contains("\"content\":\"non stream ok\""));
        assertEquals(20, usage.getInputTokens());
        assertEquals(6, usage.getOutputTokens());
    }

    @Test
    @DisplayName("OpenAI OAuth 上游流式可聚合为客户端非流式 Responses 响应")
    void forcedUpstreamStreamingCanAggregateToNonStreamingResponses() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "responses"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        UsageTokens usage = handler.captureStreamingAsNonStreaming(textOnlyResponsesSse(), response, new ResponsesUsageParser());

        assertTrue(response.getContentAsString().contains("\"object\":\"response\""));
        assertTrue(response.getContentAsString().contains("\"type\":\"output_text\""));
        assertTrue(response.getContentAsString().contains("non stream ok"));
        assertEquals(20, usage.getInputTokens());
        assertEquals(6, usage.getOutputTokens());
    }

    @Test
    @DisplayName("OpenAI Responses SSE 聚合兼容 data: 无空格格式")
    void forcedUpstreamStreamingAggregatesDataLinesWithoutSpace() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "responses"));
        String sse = """
                data:{"type":"response.created","response":{"id":"resp_no_space","model":"gpt-5.5"}}

                data:{"type":"response.output_text.delta","output_index":0,"delta":"no-space ok"}

                data:{"type":"response.completed","response":{"id":"resp_no_space","model":"gpt-5.5","status":"completed","usage":{"input_tokens":12,"output_tokens":3,"input_tokens_details":{"cached_tokens":2}}}}

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        UsageTokens usage = handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertTrue(response.getContentAsString().contains("no-space ok"));
        assertEquals(10, usage.getInputTokens());
        assertEquals(3, usage.getOutputTokens());
        assertEquals(2, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("Responses 客户端非流式聚合缺 final response 时保留原始 SSE")
    void responsesClientPreservesSseWhenUpstreamHasNoFinalResponse() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "responses"));
        String sse = """
                data: {"type":"response.created","response":{"id":"resp_partial","model":"gpt-5.5"}}

                data: {"type":"response.output_text.delta","output_index":0,"delta":"partial"}

                data: [DONE]

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("text/event-stream"));
        assertTrue(response.getContentAsString().contains("response.output_text.delta"));
        assertTrue(response.getContentAsString().contains("data: [DONE]"));
    }

    @Test
    @DisplayName("Responses 客户端非流式聚合遇到 response.incomplete 时保留原始 SSE")
    void responsesClientPreservesSseWhenTerminalIsIncomplete() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "responses"));
        String sse = """
                data: {"type":"response.created","response":{"id":"resp_incomplete","model":"gpt-5.5"}}

                data: {"type":"response.incomplete","response":{"id":"resp_incomplete","model":"gpt-5.5","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("text/event-stream"));
        assertTrue(response.getContentAsString().contains("response.incomplete"));
        assertFalse(response.getContentAsString().contains("\"object\":\"response\""));
    }

    @Test
    @DisplayName("Chat 客户端非流式聚合 response.incomplete terminal response")
    void chatClientAggregatesIncompleteTerminalResponse() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "chat_completions"));
        String sse = """
                data: {"type":"response.incomplete","response":{"id":"resp_incomplete","model":"gpt-5.5","status":"incomplete","incomplete_details":{"reason":"content_filter"},"output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"partial answer"}]}],"usage":{"input_tokens":10,"output_tokens":2,"input_tokens_details":{"cached_tokens":3}}}}

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        UsageTokens usage = handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        String body = response.getContentAsString();
        assertTrue(body.contains("\"object\":\"chat.completion\""));
        assertTrue(body.contains("\"content\":\"partial answer\""));
        assertTrue(body.contains("\"finish_reason\":\"stop\""));
        assertEquals(7, usage.getInputTokens());
        assertEquals(2, usage.getOutputTokens());
        assertEquals(3, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("Chat 客户端非流式聚合 response.canceled terminal response")
    void chatClientAggregatesCanceledTerminalResponse() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "chat_completions"));
        String sse = """
                data: {"type":"response.canceled","response":{"id":"resp_canceled","model":"gpt-5.5","status":"canceled","error":{"code":"canceled","message":"Request canceled"},"output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"partial before cancel"}]}],"usage":{"input_tokens":10,"output_tokens":2,"input_tokens_details":{"cached_tokens":3}}}}

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        UsageTokens usage = handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        String body = response.getContentAsString();
        assertTrue(body.contains("\"object\":\"chat.completion\""));
        assertTrue(body.contains("\"content\":\"partial before cancel\""));
        assertTrue(body.contains("\"finish_reason\":\"stop\""));
        assertFalse(body.contains("missing terminal event"));
        assertEquals(7, usage.getInputTokens());
        assertEquals(2, usage.getOutputTokens());
        assertEquals(3, usage.getCacheReadTokens());
    }

    @Test
    @DisplayName("Chat 客户端非流式聚合 terminal event 缺 response 时返回协议错误")
    void chatClientReturnsProtocolErrorWhenTerminalEventHasNoResponseObject() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "chat_completions"));
        String sse = """
                data: {"type":"response.output_text.delta","output_index":0,"delta":"partial"}

                data: {"type":"response.incomplete"}

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertEquals(502, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getContentAsString().contains("\"type\":\"upstream_error\""));
        assertTrue(response.getContentAsString().contains("missing terminal event"));
    }

    @Test
    @DisplayName("Chat 客户端非流式聚合缺 terminal event 时返回 Sub2API 对齐的协议错误")
    void chatClientReturnsProtocolErrorWhenUpstreamHasNoTerminalEvent() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "chat_completions"));
        String sse = """
                data: {"type":"response.created","response":{"id":"resp_partial","model":"gpt-5.5"}}

                data: [DONE]

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertEquals(502, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getContentAsString().contains("\"type\":\"upstream_error\""));
        assertTrue(response.getContentAsString().contains("missing terminal event"));
    }

    @Test
    @DisplayName("OpenAI OAuth 上游 response.failed 非流式聚合返回 upstream_error")
    void forcedUpstreamStreamingFailedResponseReturnsProtocolError() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "responses"));
        String sse = """
                data: {"type":"response.created","response":{"id":"resp_failed","model":"gpt-5.5"}}

                data: {"type":"response.failed","response":{"id":"resp_failed","model":"gpt-5.5","status":"failed","error":{"code":"server_error","message":"Upstream failed"},"usage":{"input_tokens":10,"output_tokens":0}}}

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertEquals(502, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getContentAsString().contains("\"type\":\"upstream_error\""));
        assertTrue(response.getContentAsString().contains("\"message\":\"Upstream failed\""));
    }

    @Test
    @DisplayName("OpenAI OAuth 上游 response.failed detail 可作为 Sub2API 对齐错误原因")
    void forcedUpstreamStreamingFailedResponseUsesDetailMessage() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "responses"));
        String sse = """
                data: {"type":"response.created","response":{"id":"resp_failed_detail","model":"gpt-5.5"}}

                data: {"type":"response.failed","detail":"internal detail from upstream"}

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertEquals(502, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getContentAsString().contains("\"type\":\"upstream_error\""));
        assertTrue(response.getContentAsString().contains("\"message\":\"internal detail from upstream\""));
    }

    @Test
    @DisplayName("OpenAI OAuth 上游 response.failed 顶层 error.message 可作为错误原因")
    void forcedUpstreamStreamingFailedResponseUsesTopLevelErrorMessage() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "responses"));
        String sse = """
                data: {"type":"response.created","response":{"id":"resp_failed_top_level","model":"gpt-5.5"}}

                data: {"type":"response.failed","error":{"message":"upstream processing failed"}}

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertEquals(502, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getContentAsString().contains("\"type\":\"upstream_error\""));
        assertTrue(response.getContentAsString().contains("\"message\":\"upstream processing failed\""));
    }

    @Test
    @DisplayName("OpenAI OAuth 上游流式工具调用可聚合为客户端非流式 chat tool_calls")
    void forcedUpstreamStreamingAggregatesToolCallToChat() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "chat_completions"));
        String sse = """
                data: {"type":"response.created","response":{"id":"resp_tool","model":"gpt-5.5"}}

                data: {"type":"response.output_item.added","output_index":0,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"get_weather","status":"in_progress"}}

                data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\\"city\\":"}

                data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"\\"NYC\\"}"}

                data: {"type":"response.function_call_arguments.done","output_index":0,"arguments":"{\\"city\\":\\"NYC\\"}"}

                data: {"type":"response.completed","response":{"id":"resp_tool","model":"gpt-5.5","status":"completed","usage":{"input_tokens":10,"output_tokens":2}}}

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertTrue(response.getContentAsString().contains("\"tool_calls\""));
        assertTrue(response.getContentAsString().contains("\"name\":\"get_weather\""));
        assertTrue(response.getContentAsString().contains("\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\""));
    }

    @Test
    @DisplayName("OpenAI OAuth 聚合可从 output_item.done 恢复工具参数")
    void forcedUpstreamStreamingAggregatesToolCallFromOutputItemDone() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "chat_completions"));
        String sse = """
                data: {"type":"response.created","response":{"id":"resp_tool_done","model":"gpt-5.5"}}

                data: {"type":"response.output_item.done","output_index":0,"item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"get_weather","arguments":"{\\"city\\":\\"NYC\\"}","status":"completed"}}

                data: {"type":"response.completed","response":{"id":"resp_tool_done","model":"gpt-5.5","status":"completed","usage":{"input_tokens":10,"output_tokens":2}}}

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertTrue(response.getContentAsString().contains("\"tool_calls\""));
        assertTrue(response.getContentAsString().contains("\"name\":\"get_weather\""));
        assertTrue(response.getContentAsString().contains("\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\""));
    }

    @Test
    @DisplayName("OpenAI OAuth 聚合可从 response.completed.output 恢复最终输出")
    void forcedUpstreamStreamingAggregatesFinalResponseOutput() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "chat_completions"));
        String sse = """
                data: {"type":"response.created","response":{"id":"resp_final","model":"gpt-5.5"}}

                data: {"type":"response.completed","response":{"id":"resp_final","model":"gpt-5.5","status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"final only"}]}],"usage":{"input_tokens":10,"output_tokens":2}}}

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertTrue(response.getContentAsString().contains("\"object\":\"chat.completion\""));
        assertTrue(response.getContentAsString().contains("\"content\":\"final only\""));
    }

    @Test
    @DisplayName("OpenAI OAuth 聚合使用 output_text.done 的最终文本")
    void forcedUpstreamStreamingUsesOutputTextDoneText() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "chat_completions"));
        String sse = """
                data: {"type":"response.created","response":{"id":"resp_done","model":"gpt-5.5"}}

                data: {"type":"response.output_text.delta","output_index":0,"delta":"draft"}

                data: {"type":"response.output_text.done","output_index":0,"text":"final text"}

                data: {"type":"response.completed","response":{"id":"resp_done","model":"gpt-5.5","status":"completed","usage":{"input_tokens":10,"output_tokens":2}}}

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertTrue(response.getContentAsString().contains("\"content\":\"final text\""));
        assertFalse(response.getContentAsString().contains("draft"));
    }

    @Test
    @DisplayName("OpenAI OAuth 聚合保留多个 content_index")
    void forcedUpstreamStreamingAggregatesMultipleContentParts() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "responses"));
        String sse = """
                data: {"type":"response.created","response":{"id":"resp_parts","model":"gpt-5.5"}}

                data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_1","type":"message","status":"in_progress","role":"assistant","content":[]}}

                data: {"type":"response.output_text.done","output_index":0,"content_index":1,"text":"second"}

                data: {"type":"response.content_part.done","output_index":0,"content_index":0,"part":{"type":"output_text","text":"first"}}

                data: {"type":"response.completed","response":{"id":"resp_parts","model":"gpt-5.5","status":"completed","usage":{"input_tokens":10,"output_tokens":2}}}

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        String body = response.getContentAsString();
        assertTrue(body.contains("\"text\":\"first\""));
        assertTrue(body.contains("\"text\":\"second\""));
        assertTrue(body.indexOf("\"text\":\"first\"") < body.indexOf("\"text\":\"second\""));
    }

    @Test
    @DisplayName("OpenAI OAuth 聚合保留 reasoning summary 到 Chat reasoning_content")
    void forcedUpstreamStreamingAggregatesReasoningToChat() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(oauthCodexContext(Platform.OPENAI, "chat_completions"));
        String sse = """
                data: {"type":"response.created","response":{"id":"resp_reason","model":"gpt-5.5"}}

                data: {"type":"response.output_item.added","output_index":0,"item":{"id":"rsn_1","type":"reasoning","status":"in_progress","summary":[]}}

                data: {"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"thinking"}

                data: {"type":"response.completed","response":{"id":"resp_reason","model":"gpt-5.5","status":"completed","usage":{"input_tokens":10,"output_tokens":2}}}

                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.captureStreamingAsNonStreaming(sse, response, new ResponsesUsageParser());

        assertTrue(response.getContentAsString().contains("\"reasoning_content\":\"thinking\""));
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

    @Test
    @DisplayName("非流式 passthrough 响应不做反向协议翻译")
    void passthroughNonStreamingResponseSkipsProtocolTranslation() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("test-passthrough-response")
                .requestPlatform(Platform.OPENAI)
                .requestFormat("responses")
                .requestedModel("gpt-5.5")
                .selectedAccount(AccountEntity.builder().id(1L).name("openai-api-key").platform(Platform.OPENAI).build())
                .stream(false)
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        "responses",
                        "responses",
                        EndpointKind.OPENAI_RESPONSES,
                        "https://api.openai.com/v1/responses",
                        false,
                        false,
                        "responses",
                        "openai_api_key_responses"))
                .build());
        String responsesJson = """
                {"id":"resp_passthrough","object":"response","model":"gpt-5.5","output":[],"usage":{"input_tokens":10,"output_tokens":2}}
                """;
        MockHttpServletResponse response = new MockHttpServletResponse();

        UsageTokens usage = handler.captureNonStreaming(responsesJson, response, new ResponsesUsageParser());
        String body = response.getContentAsString();

        assertTrue(body.contains("\"object\":\"response\""));
        assertFalse(body.contains("chat.completion"));
        assertEquals(10, usage.getInputTokens());
        assertEquals(2, usage.getOutputTokens());
    }

    @Test
    @DisplayName("passthrough 普通错误按 sub2api 原样返回")
    void passthroughErrorResponseKeepsRawBodyAndFilteredHeaders() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        String errorJson = "{\"error\":{\"message\":\"bad request\",\"type\":\"invalid_request_error\"}}";
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.capturePassthroughError(new InputStreamHttpResponse(
                400,
                errorJson,
                Map.of(
                        "content-type", List.of("application/problem+json"),
                        "x-request-id", List.of("rid-raw"),
                        "x-codex-turn-id", List.of("turn-raw"),
                        "set-cookie", List.of("secret"))),
                response,
                errorJson);

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/problem+json"));
        assertEquals(errorJson, response.getContentAsString());
        assertEquals("rid-raw", response.getHeader("x-request-id"));
        assertEquals("turn-raw", response.getHeader("x-codex-turn-id"));
        assertFalse(response.containsHeader("set-cookie"));
    }

    @Test
    @DisplayName("Anthropic Messages thinking/signature 400 prepares filtered retry body")
    void anthropicThinkingErrorPreparesFilteredRetryBody() throws Exception {
        TestGatewayHandler handler = new TestGatewayHandler(converterRegistry());
        GatewayRequestContext ctx = GatewayRequestContext.builder()
                .requestId("anthropic-thinking-retry")
                .requestPlatform(Platform.ANTHROPIC)
                .requestFormat("messages")
                .requestedModel("claude-sonnet-4-5")
                .selectedAccount(AccountEntity.builder().id(1L).platform(Platform.ANTHROPIC).type(AccountType.API_KEY).build())
                .upstreamRoute(new UpstreamRoute(
                        Platform.ANTHROPIC,
                        "messages",
                        "messages",
                        EndpointKind.ANTHROPIC_MESSAGES,
                        "https://api.anthropic.com/v1/messages",
                        false,
                        false,
                        "messages",
                        "anthropic_messages"))
                .build();
        AccountEntity account = AccountEntity.builder()
                .id(1L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.API_KEY)
                .build();
        String body = """
                {
                  "model":"claude-sonnet-4-5",
                  "thinking":{"type":"enabled","budget_tokens":1024},
                  "context_management":{"edits":[{"type":"clear_thinking_20251015","keep":"all"}]},
                  "messages":[{"role":"assistant","content":[
                    {"type":"thinking","thinking":"private","signature":"bad"},
                    {"type":"redacted_thinking","data":"secret"},
                    {"type":"text","text":"visible"}
                  ]}]
                }""";

        AnthropicThinkingRetryPolicy.FilteredBody filtered =
                invokeAnthropicThinkingRecovery(handler, ctx, account, 400,
                        "{\"error\":{\"message\":\"Invalid `signature` in `thinking` block\"}}",
                        body,
                        false);

        assertTrue(filtered.changed());
        assertFalse(filtered.body().contains("\"thinking\":{\"type\""));
        assertFalse(filtered.body().contains("clear_thinking_20251015"));
        assertFalse(filtered.body().contains("redacted_thinking"));
        assertTrue(filtered.body().contains("\"text\":\"private\""));
        assertTrue(filtered.body().contains("\"text\":\"visible\""));
    }

    private static ConverterRegistry converterRegistry() {
        ConverterRegistry registry = new ConverterRegistry();
        registry.register(List.of(new ResponsesConverter(), new AnthropicConverter(), new ChatCompletionsConverter()));
        return registry;
    }

    private static AnthropicThinkingRetryPolicy.FilteredBody invokeAnthropicThinkingRecovery(
            TestGatewayHandler handler,
            GatewayRequestContext ctx,
            AccountEntity account,
            int statusCode,
            String errorBody,
            String upstreamBody,
            boolean retryAlreadyTried) throws Exception {
        Method method = AbstractGatewayHandler.class.getDeclaredMethod(
                "recoverAnthropicThinkingError",
                GatewayRequestContext.class,
                AccountEntity.class,
                int.class,
                String.class,
                String.class,
                boolean.class);
        method.setAccessible(true);
        return (AnthropicThinkingRetryPolicy.FilteredBody) method.invoke(
                handler, ctx, account, statusCode, errorBody, upstreamBody, retryAlreadyTried);
    }

    private static GatewayRequestContext oauthCodexContext(Platform requestPlatform, String clientFormat) {
        return GatewayRequestContext.builder()
                .requestId("test-stream-to-json")
                .requestPlatform(requestPlatform)
                .requestFormat(clientFormat)
                .requestedModel("gpt-5.5")
                .selectedAccount(AccountEntity.builder().id(1L).name("openai-oauth").platform(Platform.OPENAI).build())
                .stream(true)
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        clientFormat,
                        "responses",
                        EndpointKind.OPENAI_CODEX_RESPONSES,
                        "https://chatgpt.com/backend-api/codex/responses",
                        true,
                        true,
                        "responses",
                        "openai_oauth_codex"))
                .build();
    }

    private static GatewayRequestContext openAiApiKeyResponsesContext(Platform requestPlatform, String clientFormat) {
        return GatewayRequestContext.builder()
                .requestId("test-api-key-stream-to-json")
                .requestPlatform(requestPlatform)
                .requestFormat(clientFormat)
                .requestedModel("gpt-5.5")
                .selectedAccount(AccountEntity.builder().id(1L).name("openai-api-key").platform(Platform.OPENAI).build())
                .stream(true)
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        clientFormat,
                        "responses",
                        EndpointKind.OPENAI_RESPONSES,
                        "https://api.openai.com/v1/responses",
                        true,
                        false,
                        "responses",
                        "openai_api_key_responses"))
                .build();
    }

    private static String textOnlyResponsesSse() {
        return """
                data: {"type":"response.created","response":{"id":"resp_1","model":"gpt-5.5"}}

                data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_1","type":"message","status":"in_progress","role":"assistant","content":[]}}

                data: {"type":"response.output_text.delta","output_index":0,"delta":"non"}

                data: {"type":"response.output_text.delta","output_index":0,"delta":" stream ok"}

                data: {"type":"response.output_text.done","output_index":0}

                data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-5.5","status":"completed","usage":{"input_tokens":100,"output_tokens":6,"input_tokens_details":{"cached_tokens":80}}}}

                """;
    }

    private static class TestGatewayHandler extends AbstractGatewayHandler {

        TestGatewayHandler(ConverterRegistry converterRegistry) {
            this(converterRegistry, null);
        }

        TestGatewayHandler(ConverterRegistry converterRegistry, OpenAiCompatSessionService openAiCompatSessionService) {
            super(null, null, null, null, null, null, null, null, null, null,
                    new ProtocolTranslationService(converterRegistry), converterRegistry,
                    new GatewayProtocolPlanner(),
                    null, null, null, null, null, null, null, null, null, null, null, openAiCompatSessionService, null, null);
        }

        UsageTokens captureStreamingUsage(String sse, IUsageParser usageParser) throws IOException {
            return handleStreaming(new InputStreamHttpResponse(sse, "text/event-stream"), new MockHttpServletResponse(),
                    GatewayRequestContext.get(), usageParser).usage();
        }

        UsageTokens captureStreamingAsNonStreaming(String sse, MockHttpServletResponse response,
                                                   IUsageParser usageParser) throws IOException {
            return handleStreamingAsNonStreaming(new InputStreamHttpResponse(sse, "text/event-stream"), response,
                    GatewayRequestContext.get(), usageParser).usage();
        }

        UsageTokens captureNonStreaming(String body, MockHttpServletResponse response,
                                        IUsageParser usageParser) throws IOException {
            return handleNonStreaming(new InputStreamHttpResponse(body, "application/json"), response, usageParser).usage();
        }

        void capturePassthroughError(InputStreamHttpResponse upstreamResp,
                                     MockHttpServletResponse response,
                                     String body) throws IOException {
            gatewayResponseService.writePassthroughError(upstreamResp, response, body);
        }

        @Override
        protected IErrorWriter getErrorWriter() {
            return null;
        }
    }

    private static class InputStreamHttpResponse implements HttpResponse<InputStream> {
        private final int status;
        private final String body;
        private final Map<String, List<String>> headers;

        InputStreamHttpResponse(String body, String contentType) {
            this(200, body, Map.of("content-type", List.of(contentType)));
        }

        InputStreamHttpResponse(int status, String body, Map<String, List<String>> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        @Override public int statusCode() { return status; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() {
            return HttpHeaders.of(headers, (k, v) -> true);
        }
        @Override public InputStream body() { return new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://example.com"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    @SuppressWarnings("unchecked")
    private static OpenAiCompatSessionService newOpenAiCompatSessionService() throws Exception {
        Constructor<OpenAiCompatSessionService> constructor =
                OpenAiCompatSessionService.class.getDeclaredConstructor(
                        RMapCache.class, RMapCache.class, RMapCache.class, RMapCache.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                mapCache(new HashMap<String, String>()),
                mapCache(new HashMap<String, String>()),
                mapCache(new HashMap<String, Boolean>()),
                mapCache(new HashMap<String, String>()));
    }

    @SuppressWarnings("unchecked")
    private static <V> RMapCache<String, V> mapCache(Map<String, V> backing) {
        return (RMapCache<String, V>) Proxy.newProxyInstance(
                AbstractGatewayHandlerTest.class.getClassLoader(),
                new Class<?>[]{RMapCache.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "get" -> backing.get((String) args[0]);
                        case "put" -> {
                            backing.put((String) args[0], (V) args[1]);
                            yield null;
                        }
                        case "remove" -> backing.remove((String) args[0]);
                        case "isEmpty" -> backing.isEmpty();
                        case "size" -> backing.size();
                        case "clear" -> {
                            backing.clear();
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        return null;
    }

}
