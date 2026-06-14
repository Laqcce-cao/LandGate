package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.trigger.gateway.converter.AnthropicConverter;
import com.landgate.trigger.gateway.converter.ChatCompletionsConverter;
import com.landgate.trigger.gateway.converter.ConverterRegistry;
import com.landgate.trigger.gateway.converter.ProtocolTranslationService;
import com.landgate.trigger.gateway.converter.ResponsesConverter;
import com.landgate.trigger.gateway.oauth.AnthropicToolNameRewrite;
import com.landgate.trigger.gateway.response.GatewayResponseService;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.trigger.gateway.usage.AnthropicUsageParser;
import com.landgate.trigger.gateway.usage.IUsageParser;
import com.landgate.trigger.gateway.usage.OpenAiUsageParser;
import com.landgate.trigger.gateway.usage.ResponsesUsageParser;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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

@DisplayName("Gateway route matrix response chain tests")
class GatewayRouteMatrixResponseTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConverterRegistry converterRegistry = converterRegistry();
    private final ProtocolTranslationService translationService = new ProtocolTranslationService(converterRegistry);
    private final GatewayResponseService responseService = new GatewayResponseService(null, translationService, converterRegistry);

    @AfterEach
    void tearDown() {
        GatewayRequestContext.clear();
    }

    @Test
    @DisplayName("上游非流式响应按完整 12 条 route matrix 翻译回客户端格式")
    void nonStreamingResponsesFollowFullCoreRouteMatrix() throws Exception {
        List<ResponseMatrixCase> cases = List.of(
                matrix("messages <- Anthropic messages", Platform.ANTHROPIC, AccountType.API_KEY,
                        Platform.ANTHROPIC, "messages", "messages", EndpointKind.ANTHROPIC_MESSAGES),
                matrix("responses <- Anthropic messages", Platform.ANTHROPIC, AccountType.API_KEY,
                        Platform.OPENAI, "responses", "messages", EndpointKind.ANTHROPIC_MESSAGES),
                matrix("chat <- Anthropic messages", Platform.ANTHROPIC, AccountType.API_KEY,
                        Platform.OPENAI, "chat_completions", "messages", EndpointKind.ANTHROPIC_MESSAGES),

                matrix("messages <- OpenAI OAuth Codex responses", Platform.OPENAI, AccountType.OAUTH,
                        Platform.ANTHROPIC, "messages", "responses", EndpointKind.OPENAI_CODEX_RESPONSES),
                matrix("responses <- OpenAI OAuth Codex responses", Platform.OPENAI, AccountType.OAUTH,
                        Platform.OPENAI, "responses", "responses", EndpointKind.OPENAI_CODEX_RESPONSES),
                matrix("chat <- OpenAI OAuth Codex responses", Platform.OPENAI, AccountType.OAUTH,
                        Platform.OPENAI, "chat_completions", "responses", EndpointKind.OPENAI_CODEX_RESPONSES),

                matrix("messages <- OpenAI API Key responses", Platform.OPENAI, AccountType.API_KEY,
                        Platform.ANTHROPIC, "messages", "responses", EndpointKind.OPENAI_RESPONSES),
                matrix("responses <- OpenAI API Key responses", Platform.OPENAI, AccountType.API_KEY,
                        Platform.OPENAI, "responses", "responses", EndpointKind.OPENAI_RESPONSES),
                matrix("chat <- OpenAI API Key responses", Platform.OPENAI, AccountType.API_KEY,
                        Platform.OPENAI, "chat_completions", "responses", EndpointKind.OPENAI_RESPONSES),

                matrix("messages <- OpenAI API Key chat", Platform.OPENAI, AccountType.API_KEY,
                        Platform.ANTHROPIC, "messages", "chat_completions", EndpointKind.OPENAI_CHAT_COMPLETIONS),
                matrix("responses <- OpenAI API Key chat", Platform.OPENAI, AccountType.API_KEY,
                        Platform.OPENAI, "responses", "chat_completions", EndpointKind.OPENAI_CHAT_COMPLETIONS),
                matrix("chat <- OpenAI API Key chat", Platform.OPENAI, AccountType.API_KEY,
                        Platform.OPENAI, "chat_completions", "chat_completions", EndpointKind.OPENAI_CHAT_COMPLETIONS)
        );

        for (ResponseMatrixCase tc : cases) {
            MockHttpServletResponse servletResponse = new MockHttpServletResponse();
            UpstreamRoute route = route(tc);
            GatewayRequestContext.set(GatewayRequestContext.builder()
                    .requestId("resp_matrix_" + tc.name().replace(' ', '_'))
                    .requestPlatform(tc.requestPlatform())
                    .requestFormat(tc.clientFormat())
                    .requestedModel("gpt-5.5")
                    .selectedAccount(account(tc.accountPlatform(), tc.accountType()))
                    .upstreamRoute(route)
                    .build());

            var result = responseService.handleNonStreaming(
                    new InputStreamHttpResponse(upstreamBody(tc.upstreamFormat()), "application/json"),
                    servletResponse,
                    usageParser(tc.upstreamFormat()));

            assertEquals(200, servletResponse.getStatus(), tc.name());
            assertTrue(servletResponse.getContentType().startsWith("application/json"), tc.name());
            JsonNode clientBody = JSON.readTree(servletResponse.getContentAsString());
            assertClientShape(tc.clientFormat(), clientBody, tc.name());
            assertUsage(tc.upstreamFormat(), result.usage(), tc.name());
        }
    }

    @Test
    @DisplayName("Anthropic OAuth mimicry 非流式响应在解析和翻译前还原工具名")
    void anthropicOAuthMimicryRestoresToolNamesBeforeNonStreamingResponseHandling() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("restore_tool_names_non_stream")
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
                        false,
                        false,
                        "messages",
                        "anthropic_oauth_tool_restore"))
                .shouldMimicClaudeCode(true)
                .anthropicToolNameRewrite(AnthropicToolNameRewrite.fromToolNames(List.of("sessions_read")))
                .build());

        String upstreamBody = """
                {
                  "id":"msg_tool",
                  "type":"message",
                  "role":"assistant",
                  "model":"claude-sonnet-4-5",
                  "content":[{"type":"tool_use","id":"toolu_1","name":"cc_sess_read","input":{}}],
                  "stop_reason":"tool_use",
                  "stop_sequence":null,
                  "usage":{"input_tokens":10,"output_tokens":2}
                }""";
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        responseService.handleNonStreaming(
                new InputStreamHttpResponse(upstreamBody, "application/json"),
                servletResponse,
                new AnthropicUsageParser());

        JsonNode clientBody = JSON.readTree(servletResponse.getContentAsString());
        assertEquals("sessions_read", clientBody.get("content").get(0).get("name").asText());
        assertFalse(servletResponse.getContentAsString().contains("cc_sess_read"));
    }

    @Test
    @DisplayName("Anthropic OAuth non-streaming cache TTL usage override rewrites body before client response")
    void anthropicOAuthCacheTtlUsageOverrideRewritesNonStreamingBody() throws Exception {
        AccountEntity account = AccountEntity.builder()
                .id(1L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.OAUTH)
                .name("anthropic-oauth")
                .extra("""
                        {"cache_ttl_override_enabled":true,"cache_ttl_override_target":"1h"}
                        """)
                .build();
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("anthropic_cache_ttl_override_body")
                .requestPlatform(Platform.ANTHROPIC)
                .requestFormat("messages")
                .requestedModel("claude-sonnet-4-5")
                .selectedAccount(account)
                .upstreamRoute(new UpstreamRoute(
                        Platform.ANTHROPIC,
                        "messages",
                        "messages",
                        EndpointKind.ANTHROPIC_MESSAGES,
                        "https://upstream.example.com/v1/messages",
                        false,
                        false,
                        "messages",
                        "anthropic_oauth_cache_ttl_override"))
                .build());

        String upstreamBody = """
                {
                  "id":"msg_cache",
                  "type":"message",
                  "role":"assistant",
                  "model":"claude-sonnet-4-5",
                  "content":[{"type":"text","text":"cached"}],
                  "stop_reason":"end_turn",
                  "stop_sequence":null,
                  "usage":{
                    "input_tokens":10,
                    "output_tokens":2,
                    "cache_creation":{
                      "ephemeral_5m_input_tokens":3,
                      "ephemeral_1h_input_tokens":4
                    }
                  }
                }""";
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        UsageTokens usage = responseService.handleNonStreaming(
                        new InputStreamHttpResponse(upstreamBody, "application/json"),
                        servletResponse,
                        new AnthropicUsageParser())
                .usage();

        JsonNode clientBody = JSON.readTree(servletResponse.getContentAsString());
        JsonNode cacheCreation = clientBody.path("usage").path("cache_creation");
        assertEquals(0, cacheCreation.path("ephemeral_5m_input_tokens").asInt());
        assertEquals(7, cacheCreation.path("ephemeral_1h_input_tokens").asInt());
        assertEquals(0, usage.getCacheCreation5mTokens());
        assertEquals(7, usage.getCacheCreation1hTokens());
    }

    @Test
    @DisplayName("Anthropic cached_tokens compatibility is normalized before Responses translation")
    void anthropicCachedTokensCompatibilityBeforeResponsesTranslation() throws Exception {
        GatewayRequestContext.set(GatewayRequestContext.builder()
                .requestId("anthropic_cached_tokens_to_responses")
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
                        false,
                        false,
                        "messages",
                        "anthropic_cached_tokens_compat"))
                .build());

        String upstreamBody = """
                {
                  "id":"msg_cached",
                  "type":"message",
                  "role":"assistant",
                  "model":"claude-sonnet-4-5",
                  "content":[{"type":"text","text":"cached"}],
                  "stop_reason":"end_turn",
                  "stop_sequence":null,
                  "usage":{
                    "input_tokens":10,
                    "output_tokens":2,
                    "cached_tokens":9,
                    "cache_creation":{
                      "ephemeral_5m_input_tokens":3,
                      "ephemeral_1h_input_tokens":5
                    }
                  }
                }""";
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        UsageTokens usage = responseService.handleNonStreaming(
                        new InputStreamHttpResponse(upstreamBody, "application/json"),
                        servletResponse,
                        new AnthropicUsageParser())
                .usage();

        JsonNode clientBody = JSON.readTree(servletResponse.getContentAsString());
        JsonNode clientUsage = clientBody.path("usage");
        assertEquals(9, clientUsage.path("input_tokens_details").path("cached_tokens").asInt());
        assertEquals(8, usage.getCacheCreationTokens());
        assertEquals(9, usage.getCacheReadTokens());
    }

    private static void assertClientShape(String clientFormat, JsonNode root, String name) {
        switch (clientFormat) {
            case "messages" -> {
                assertEquals("message", root.path("type").asText(), name);
                assertEquals("assistant", root.path("role").asText(), name);
                assertEquals("Hello from upstream", root.path("content").get(0).path("text").asText(), name);
            }
            case "responses" -> {
                assertEquals("response", root.path("object").asText(), name);
                assertEquals("completed", root.path("status").asText(), name);
                assertEquals("Hello from upstream", root.path("output").get(0).path("content").get(0).path("text").asText(), name);
            }
            case "chat_completions" -> {
                assertEquals("chat.completion", root.path("object").asText(), name);
                assertEquals("assistant", root.path("choices").get(0).path("message").path("role").asText(), name);
                assertEquals("Hello from upstream", root.path("choices").get(0).path("message").path("content").asText(), name);
            }
            default -> fail("unknown client format " + clientFormat);
        }
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

    private static String upstreamBody(String upstreamFormat) {
        return switch (upstreamFormat) {
            case "messages" -> """
                    {
                      "id":"msg_upstream",
                      "type":"message",
                      "role":"assistant",
                      "model":"claude-sonnet-4-5",
                      "content":[{"type":"text","text":"Hello from upstream"}],
                      "stop_reason":"end_turn",
                      "stop_sequence":null,
                      "usage":{
                        "input_tokens":10,
                        "output_tokens":2,
                        "cache_read_input_tokens":3
                      }
                    }""";
            case "responses" -> """
                    {
                      "id":"resp_upstream",
                      "object":"response",
                      "model":"gpt-5.5",
                      "status":"completed",
                      "output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Hello from upstream"}]}],
                      "usage":{
                        "input_tokens":10,
                        "output_tokens":2,
                        "input_tokens_details":{"cached_tokens":3}
                      }
                    }""";
            case "chat_completions" -> """
                    {
                      "id":"chatcmpl_upstream",
                      "object":"chat.completion",
                      "created":1,
                      "model":"gpt-5.5",
                      "choices":[{"index":0,"message":{"role":"assistant","content":"Hello from upstream"},"finish_reason":"stop"}],
                      "usage":{
                        "prompt_tokens":10,
                        "completion_tokens":2,
                        "prompt_tokens_details":{"cached_tokens":3}
                      }
                    }""";
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

    private static UpstreamRoute route(ResponseMatrixCase tc) {
        return new UpstreamRoute(
                tc.accountPlatform(),
                tc.clientFormat(),
                tc.upstreamFormat(),
                tc.endpointKind(),
                "https://upstream.example.com",
                false,
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

    private static ResponseMatrixCase matrix(String name,
                                             Platform accountPlatform,
                                             AccountType accountType,
                                             Platform requestPlatform,
                                             String clientFormat,
                                             String upstreamFormat,
                                             EndpointKind endpointKind) {
        return new ResponseMatrixCase(name, accountPlatform, accountType,
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

    private record ResponseMatrixCase(
            String name,
            Platform accountPlatform,
            AccountType accountType,
            Platform requestPlatform,
            String clientFormat,
            String upstreamFormat,
            EndpointKind endpointKind
    ) {
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
