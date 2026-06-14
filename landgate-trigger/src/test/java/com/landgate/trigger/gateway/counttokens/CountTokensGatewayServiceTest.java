package com.landgate.trigger.gateway.counttokens;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.infrastructure.upstream.HttpUpstreamClient;
import com.landgate.trigger.gateway.access.GatewayAccessResult;
import com.landgate.trigger.gateway.access.GatewayAccessService;
import com.landgate.trigger.gateway.account.AccountSelector;
import com.landgate.trigger.gateway.error.AnthropicErrorWriter;
import com.landgate.trigger.gateway.forwarding.AnthropicForwardingRuntimePolicyProvider;
import com.landgate.trigger.gateway.oauth.BillingHeaderService;
import com.landgate.trigger.gateway.oauth.ClaudeCodeDetector;
import com.landgate.trigger.gateway.oauth.FingerprintService;
import com.landgate.trigger.gateway.oauth.GetAccessTokenService;
import com.landgate.trigger.gateway.oauth.OAuthMimicryService;
import com.landgate.trigger.gateway.oauth.UserIdRewriter;
import com.landgate.trigger.gateway.request.AnthropicMessagesHttpRequestValidator;
import com.landgate.trigger.gateway.transformer.AnthropicCountTokensRequestFactory;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.AnthropicCountTokensPolicy;
import com.landgate.types.gateway.GatewayUnsupportedFeaturePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CountTokensGatewayService")
class CountTokensGatewayServiceTest {

    private final GatewayAccessService gatewayAccessService = mock(GatewayAccessService.class);
    private final AccountSelector accountSelector = mock(AccountSelector.class);
    private final GetAccessTokenService getAccessTokenService = mock(GetAccessTokenService.class);
    private final HttpUpstreamClient httpUpstreamClient = mock(HttpUpstreamClient.class);
    private final FingerprintService fingerprintService = new FingerprintService();
    private final OAuthMimicryService oAuthMimicryService = new OAuthMimicryService();
    private final AnthropicCountTokensRequestFactory requestFactory =
            new AnthropicCountTokensRequestFactory(oAuthMimicryService, fingerprintService);
    private final AnthropicCountTokensOAuthNormalizer oAuthNormalizer =
            new AnthropicCountTokensOAuthNormalizer(
                    fingerprintService,
                    new UserIdRewriter(),
                    new BillingHeaderService(),
                    oAuthMimicryService);
    private final AnthropicErrorWriter errorWriter = new AnthropicErrorWriter();

    private final CountTokensGatewayService service = new CountTokensGatewayService(
            gatewayAccessService,
            accountSelector,
            getAccessTokenService,
            httpUpstreamClient,
            requestFactory,
            oAuthNormalizer,
            new AnthropicCountTokensThinkingRetryPolicy(),
            new AnthropicForwardingRuntimePolicyProvider(true, false, false, ""),
            new AnthropicMessagesHttpRequestValidator(),
            errorWriter,
            new ClaudeCodeDetector());

    @Test
    @DisplayName("selected OpenAI account returns Sub2API-style 404 fallback")
    void openAiSelectedAccountReturnsUnsupportedPlatform404() throws Exception {
        GroupEntity group = group();
        AccountEntity openAi = AccountEntity.builder()
                .id(10L)
                .name("openai")
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .build();
        when(gatewayAccessService.check(any(), any(), any(), eq(errorWriter)))
                .thenReturn(access(group));
        when(accountSelector.selectAccount(group, "claude-sonnet-4-5")).thenReturn(openAi);

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.handle(countTokensBody(), request(), response);

        assertEquals(AnthropicCountTokensPolicy.STATUS_NOT_FOUND, response.getStatus());
        assertTrue(response.getContentAsString().contains(GatewayUnsupportedFeaturePolicy.ERROR_TYPE_NOT_FOUND));
        assertTrue(response.getContentAsString()
                .contains(GatewayUnsupportedFeaturePolicy.COUNT_TOKENS_UNSUPPORTED_PLATFORM_MESSAGE));
        verify(httpUpstreamClient, never()).send(any());
    }

    @Test
    @DisplayName("Anthropic API key account forwards to count_tokens endpoint with upstream x-api-key")
    void anthropicApiKeyForwardsToUpstreamCountTokens() throws Exception {
        GroupEntity group = group();
        AccountEntity account = AccountEntity.builder()
                .id(11L)
                .name("anthropic")
                .platform(Platform.ANTHROPIC)
                .type(AccountType.API_KEY)
                .build();
        when(gatewayAccessService.check(any(), any(), any(), eq(errorWriter)))
                .thenReturn(access(group));
        when(accountSelector.selectAccount(group, "claude-sonnet-4-5")).thenReturn(account);
        when(getAccessTokenService.getAccessToken(account)).thenReturn("sk-ant-test");
        when(httpUpstreamClient.send(any())).thenReturn(new InputStreamHttpResponse(
                200,
                "{\"input_tokens\":42}",
                Map.of("Content-Type", List.of("application/json"))));

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.handle(countTokensBody(), request(), response);

        assertEquals(200, response.getStatus());
        assertEquals("{\"input_tokens\":42}", response.getContentAsString());

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpUpstreamClient).send(requestCaptor.capture());
        HttpRequest upstreamRequest = requestCaptor.getValue();
        assertEquals(URI.create("https://api.anthropic.com" + AnthropicCountTokensPolicy.UPSTREAM_PATH_WITH_QUERY),
                upstreamRequest.uri());
        assertEquals(Optional.of("sk-ant-test"), upstreamRequest.headers().firstValue("x-api-key"));
        assertEquals(Optional.empty(), upstreamRequest.headers().firstValue("Authorization"));
        assertEquals(Optional.of("2023-06-01"), upstreamRequest.headers().firstValue("anthropic-version"));
    }

    @Test
    @DisplayName("upstream count_tokens unsupported 404 is normalized for client fallback")
    void upstreamUnsupported404IsNormalized() throws Exception {
        GroupEntity group = group();
        AccountEntity account = AccountEntity.builder()
                .id(12L)
                .name("anthropic-relay")
                .platform(Platform.ANTHROPIC)
                .type(AccountType.API_KEY)
                .build();
        when(gatewayAccessService.check(any(), any(), any(), eq(errorWriter)))
                .thenReturn(access(group));
        when(accountSelector.selectAccount(group, "claude-sonnet-4-5")).thenReturn(account);
        when(getAccessTokenService.getAccessToken(account)).thenReturn("sk-ant-test");
        when(httpUpstreamClient.send(any())).thenReturn(new InputStreamHttpResponse(
                404,
                "{\"error\":{\"message\":\"Not found: /v1/messages/count_tokens\",\"type\":\"not_found_error\"}}",
                Map.of("Content-Type", List.of("application/json"))));

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.handle(countTokensBody(), request(), response);

        assertEquals(AnthropicCountTokensPolicy.STATUS_NOT_FOUND, response.getStatus());
        assertTrue(response.getContentAsString()
                .contains(GatewayUnsupportedFeaturePolicy.COUNT_TOKENS_UNSUPPORTED_UPSTREAM_MESSAGE));
    }

    @Test
    @DisplayName("Anthropic OAuth count_tokens uses selected bearer and token-counting mimicry beta")
    void anthropicOAuthAddsCountTokensMimicryHeaders() throws Exception {
        GroupEntity group = group();
        AccountEntity account = AccountEntity.builder()
                .id(13L)
                .name("anthropic-oauth")
                .platform(Platform.ANTHROPIC)
                .type(AccountType.OAUTH)
                .credentials("{\"access_token\":\"oauth-token\"}")
                .build();
        when(gatewayAccessService.check(any(), any(), any(), eq(errorWriter)))
                .thenReturn(access(group));
        when(accountSelector.selectAccount(group, "claude-sonnet-4-5")).thenReturn(account);
        when(getAccessTokenService.getAccessToken(account)).thenReturn("oauth-token");
        when(httpUpstreamClient.send(any())).thenReturn(new InputStreamHttpResponse(
                200,
                "{\"input_tokens\":9}",
                Map.of("Content-Type", List.of("application/json"))));

        MockHttpServletRequest request = request();
        request.addHeader("User-Agent", "curl/8.0");
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.handle(countTokensBody(), request, response);

        assertEquals(200, response.getStatus());
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpUpstreamClient).send(requestCaptor.capture());
        HttpRequest upstreamRequest = requestCaptor.getValue();
        assertEquals(Optional.of("Bearer oauth-token"), upstreamRequest.headers().firstValue("Authorization"));
        assertEquals(Optional.empty(), upstreamRequest.headers().firstValue("x-api-key"));
        String beta = upstreamRequest.headers().firstValue("anthropic-beta").orElse("");
        assertTrue(beta.contains("claude-code-20250219"));
        assertTrue(beta.contains("oauth-2025-04-20"));
        assertTrue(beta.contains("interleaved-thinking-2025-05-14"));
        assertTrue(beta.contains("token-counting-2024-11-01"));
        assertEquals(Optional.of("application/json"), upstreamRequest.headers().firstValue("Accept"));
        assertTrue(upstreamRequest.headers().firstValue("x-client-request-id").isPresent());
    }

    @Test
    @DisplayName("thinking signature 400 retries once with filtered request")
    void thinkingSignatureErrorRetriesOnce() throws Exception {
        GroupEntity group = group();
        AccountEntity account = AccountEntity.builder()
                .id(14L)
                .name("anthropic")
                .platform(Platform.ANTHROPIC)
                .type(AccountType.API_KEY)
                .build();
        when(gatewayAccessService.check(any(), any(), any(), eq(errorWriter)))
                .thenReturn(access(group));
        when(accountSelector.selectAccount(group, "claude-sonnet-4-5")).thenReturn(account);
        when(getAccessTokenService.getAccessToken(account)).thenReturn("sk-ant-test");
        when(httpUpstreamClient.send(any()))
                .thenReturn(new InputStreamHttpResponse(
                        400,
                        "{\"error\":{\"message\":\"Invalid `signature` in `thinking` block\"}}",
                        Map.of("Content-Type", List.of("application/json"))))
                .thenReturn(new InputStreamHttpResponse(
                        200,
                        "{\"input_tokens\":7}",
                        Map.of("Content-Type", List.of("application/json"))));

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.handle(countTokensBodyWithThinking(), request(), response);

        assertEquals(200, response.getStatus());
        assertEquals("{\"input_tokens\":7}", response.getContentAsString());
        verify(httpUpstreamClient, org.mockito.Mockito.times(2)).send(any());
    }

    private static GatewayAccessResult access(GroupEntity group) {
        return new GatewayAccessResult(false, 1L, 2L, group.getId(), group, null);
    }

    private static GroupEntity group() {
        return GroupEntity.builder()
                .id(1L)
                .name("default")
                .supportedProtocols("[\"messages\"]")
                .build();
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", AnthropicCountTokensPolicy.CLIENT_PATH);
        request.addHeader("Authorization", "Bearer landgate-key");
        request.addHeader("anthropic-beta", "token-counting-2024-11-01");
        request.setAttribute("api_key_id", 1L);
        request.setAttribute("user_id", 2L);
        request.setAttribute("group_id", 1L);
        return request;
    }

    private static String countTokensBody() {
        return "{\"model\":\"claude-sonnet-4-5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    }

    private static String countTokensBodyWithThinking() {
        return """
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
    }

    private record InputStreamHttpResponse(
            int statusCode,
            String bodyText,
            Map<String, List<String>> responseHeaders
    ) implements HttpResponse<InputStream> {
        @Override
        public InputStream body() {
            return new ByteArrayInputStream(bodyText.getBytes());
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(responseHeaders, (k, v) -> true);
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
        public URI uri() {
            return URI.create("https://api.anthropic.com" + AnthropicCountTokensPolicy.UPSTREAM_PATH_WITH_QUERY);
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_2;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }
    }
}
