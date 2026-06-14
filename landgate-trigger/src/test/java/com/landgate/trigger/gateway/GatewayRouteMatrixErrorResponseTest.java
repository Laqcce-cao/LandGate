package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.trigger.gateway.error.AnthropicErrorWriter;
import com.landgate.trigger.gateway.error.ErrorPassthroughService;
import com.landgate.trigger.gateway.error.IErrorWriter;
import com.landgate.trigger.gateway.error.OpenAiErrorWriter;
import com.landgate.trigger.gateway.response.GatewayResponseService;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.ErrorResponsePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
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

@DisplayName("Gateway route matrix error response tests")
class GatewayRouteMatrixErrorResponseTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UPSTREAM_ERROR_BODY = """
            {
              "error":{
                "message":"upstream rejected raw body",
                "type":"invalid_request_error",
                "code":"upstream_specific_code"
              },
              "provider_account":"acct_should_not_leak"
            }""";

    private final GatewayProtocolPlanner protocolPlanner = new GatewayProtocolPlanner();
    private final GatewayResponseService responseService = new GatewayResponseService(null, null, null);
    private final ErrorPassthroughService errorPassthroughService = new ErrorPassthroughService();

    @Test
    @DisplayName("非重试 HTTP 错误按完整 12 条 route matrix 区分原样透传与客户端协议安全 envelope")
    void nonRetryErrorsFollowFullCoreRouteMatrix() throws Exception {
        List<ErrorMatrixCase> cases = List.of(
                matrix("messages <- Anthropic messages error", Platform.ANTHROPIC, AccountType.API_KEY,
                        Platform.ANTHROPIC, "messages", "messages", EndpointKind.ANTHROPIC_MESSAGES),
                matrix("responses <- Anthropic messages error", Platform.ANTHROPIC, AccountType.API_KEY,
                        Platform.OPENAI, "responses", "messages", EndpointKind.ANTHROPIC_MESSAGES),
                matrix("chat <- Anthropic messages error", Platform.ANTHROPIC, AccountType.API_KEY,
                        Platform.OPENAI, "chat_completions", "messages", EndpointKind.ANTHROPIC_MESSAGES),

                matrix("messages <- OpenAI OAuth Codex responses error", Platform.OPENAI, AccountType.OAUTH,
                        Platform.ANTHROPIC, "messages", "responses", EndpointKind.OPENAI_CODEX_RESPONSES),
                matrix("responses <- OpenAI OAuth Codex responses error", Platform.OPENAI, AccountType.OAUTH,
                        Platform.OPENAI, "responses", "responses", EndpointKind.OPENAI_CODEX_RESPONSES),
                matrix("chat <- OpenAI OAuth Codex responses error", Platform.OPENAI, AccountType.OAUTH,
                        Platform.OPENAI, "chat_completions", "responses", EndpointKind.OPENAI_CODEX_RESPONSES),

                matrix("messages <- OpenAI API Key responses error", Platform.OPENAI, AccountType.API_KEY,
                        Platform.ANTHROPIC, "messages", "responses", EndpointKind.OPENAI_RESPONSES),
                matrix("responses <- OpenAI API Key responses error", Platform.OPENAI, AccountType.API_KEY,
                        Platform.OPENAI, "responses", "responses", EndpointKind.OPENAI_RESPONSES),
                matrix("chat <- OpenAI API Key responses error", Platform.OPENAI, AccountType.API_KEY,
                        Platform.OPENAI, "chat_completions", "responses", EndpointKind.OPENAI_RESPONSES),

                matrix("messages <- OpenAI API Key chat error", Platform.OPENAI, AccountType.API_KEY,
                        Platform.ANTHROPIC, "messages", "chat_completions", EndpointKind.OPENAI_CHAT_COMPLETIONS),
                matrix("responses <- OpenAI API Key chat error", Platform.OPENAI, AccountType.API_KEY,
                        Platform.OPENAI, "responses", "chat_completions", EndpointKind.OPENAI_CHAT_COMPLETIONS),
                matrix("chat <- OpenAI API Key chat error", Platform.OPENAI, AccountType.API_KEY,
                        Platform.OPENAI, "chat_completions", "chat_completions", EndpointKind.OPENAI_CHAT_COMPLETIONS)
        );

        for (ErrorMatrixCase tc : cases) {
            UpstreamRoute route = route(tc);
            GatewayProtocolPlan plan = protocolPlanner.plan(tc.requestPlatform(), route);
            MockHttpServletResponse servletResponse = new MockHttpServletResponse();

            assertEquals(plan.passthrough(), tc.clientFormat().equals(tc.upstreamFormat()), tc.name());
            assertEquals(ErrorPassthroughService.ErrorAction.MASK,
                    errorPassthroughService.decide(400, UPSTREAM_ERROR_BODY, tc.accountPlatform().name()), tc.name());

            if (plan.passthrough()) {
                responseService.writePassthroughError(upstreamErrorResponse(), servletResponse, UPSTREAM_ERROR_BODY);
                assertPassthroughError(servletResponse, tc.name());
            } else {
                writeMaskedError(tc.clientFormat(), servletResponse, 400, UPSTREAM_ERROR_BODY);
                assertMaskedError(tc.clientFormat(), servletResponse, tc.name());
            }
        }
    }

    @Test
    @DisplayName("模型/区域类上游错误保持 failover 裁决，不直接暴露给客户端")
    void retryableUpstreamErrorsStayInFailoverDecision() {
        assertEquals(ErrorPassthroughService.ErrorAction.RETRY,
                errorPassthroughService.decide(404,
                        "{\"error\":{\"message\":\"model_not_found: old model\"}}",
                        Platform.OPENAI.name()));
        assertEquals(ErrorPassthroughService.ErrorAction.RETRY,
                errorPassthroughService.decide(403,
                        "{\"error\":{\"message\":\"not available in your region\"}}",
                        Platform.ANTHROPIC.name()));
    }

    @Test
    @DisplayName("OpenAI transient processing 400 按 Sub2API 进入 failover，普通 validation 仍 MASK")
    void openAiTransientProcessingErrorRetriesOnlyForOpenAi() {
        String transientBody = """
                {"error":{"message":"An error occurred while processing your request. You can retry your request, or contact us through our help center at help.openai.com if the error persists. Please include the request ID req_123 in your message."}}
                """;

        assertEquals(ErrorPassthroughService.ErrorAction.RETRY,
                errorPassthroughService.decide(400, transientBody, Platform.OPENAI.name()));
        assertEquals(ErrorPassthroughService.ErrorAction.MASK,
                errorPassthroughService.decide(400, transientBody, Platform.ANTHROPIC.name()));
        assertEquals(ErrorPassthroughService.ErrorAction.MASK,
                errorPassthroughService.decide(400,
                        "{\"error\":{\"message\":\"Missing required parameter: 'instructions'\"}}",
                        Platform.OPENAI.name()));
    }

    @Test
    @DisplayName("OpenAI 402/403 按 Sub2API OpenAI gateway failover，非 OpenAI 不放大")
    void openAiPaymentAndForbiddenErrorsFailover() {
        String billingBody = "{\"error\":{\"message\":\"billing issue\"}}";
        String forbiddenBody = "{\"error\":{\"message\":\"policy denied\"}}";

        assertEquals(ErrorPassthroughService.ErrorAction.RETRY,
                errorPassthroughService.decide(402, billingBody, Platform.OPENAI.name()));
        assertEquals(ErrorPassthroughService.ErrorAction.RETRY,
                errorPassthroughService.decide(403, forbiddenBody, Platform.OPENAI.name()));

        assertEquals(ErrorPassthroughService.ErrorAction.MASK,
                errorPassthroughService.decide(402, billingBody, Platform.ANTHROPIC.name()));
        assertEquals(ErrorPassthroughService.ErrorAction.MASK,
                errorPassthroughService.decide(403, forbiddenBody, Platform.ANTHROPIC.name()));
    }

    private static void assertPassthroughError(MockHttpServletResponse response, String name) throws Exception {
        assertEquals(400, response.getStatus(), name);
        assertEquals("application/vnd.upstream+json;charset=UTF-8", response.getContentType(), name);
        assertEquals(UPSTREAM_ERROR_BODY, response.getContentAsString(), name);
        assertEquals("rid_upstream", response.getHeader("x-request-id"), name);
        assertEquals("turn_state", response.getHeader("x-codex-turn-state"), name);
        assertNull(response.getHeader("authorization"), name);
    }

    private static void assertMaskedError(String clientFormat, MockHttpServletResponse response, String name) throws Exception {
        assertEquals(400, response.getStatus(), name);
        assertTrue(response.getContentType().startsWith("application/json"), name);
        assertFalse(response.getContentAsString().contains("provider_account"), name);
        assertFalse(response.getContentAsString().contains("upstream_specific_code"), name);
        assertNull(response.getHeader("x-codex-turn-state"), name);

        JsonNode body = JSON.readTree(response.getContentAsString());
        if ("messages".equals(clientFormat)) {
            assertEquals("error", body.path("type").asText(), name);
            assertEquals("invalid_request_error", body.path("error").path("type").asText(), name);
            assertEquals("upstream rejected raw body", body.path("error").path("message").asText(), name);
        } else {
            assertEquals("invalid_request_error", body.path("error").path("type").asText(), name);
            assertEquals("upstream rejected raw body", body.path("error").path("message").asText(), name);
            assertTrue(body.path("error").path("param").isNull(), name);
            assertTrue(body.path("error").path("code").isNull(), name);
        }
    }

    private static void writeMaskedError(String clientFormat,
                                         MockHttpServletResponse response,
                                         int status,
                                         String upstreamBody) throws Exception {
        IErrorWriter writer = "messages".equals(clientFormat)
                ? new AnthropicErrorWriter()
                : new OpenAiErrorWriter();
        writer.writeError(response, status,
                ErrorResponsePolicy.errorCodeForStatus(status),
                ErrorResponsePolicy.safeMessageForStatus(status, upstreamBody));
    }

    private static HttpResponse<InputStream> upstreamErrorResponse() {
        return new InputStreamHttpResponse(400, UPSTREAM_ERROR_BODY, Map.of(
                "content-type", List.of("application/vnd.upstream+json"),
                "x-request-id", List.of("rid_upstream"),
                "x-codex-turn-state", List.of("turn_state"),
                "authorization", List.of("Bearer should_not_copy"),
                "content-length", List.of("9999")
        ));
    }

    private static UpstreamRoute route(ErrorMatrixCase tc) {
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

    private static ErrorMatrixCase matrix(String name,
                                          Platform accountPlatform,
                                          AccountType accountType,
                                          Platform requestPlatform,
                                          String clientFormat,
                                          String upstreamFormat,
                                          EndpointKind endpointKind) {
        return new ErrorMatrixCase(name, accountPlatform, accountType,
                requestPlatform, clientFormat, upstreamFormat, endpointKind);
    }

    private record ErrorMatrixCase(
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

        InputStreamHttpResponse(int status, String body, Map<String, List<String>> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        @Override public int statusCode() { return status; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(headers, (k, v) -> true); }
        @Override public InputStream body() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://upstream.example.com"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
