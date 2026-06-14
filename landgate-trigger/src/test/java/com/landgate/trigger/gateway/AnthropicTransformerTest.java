package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.oauth.BillingHeaderService;
import com.landgate.trigger.gateway.oauth.FingerprintService;
import com.landgate.trigger.gateway.forwarding.AnthropicForwardingRuntimePolicyProvider;
import com.landgate.trigger.gateway.oauth.OAuthMimicryService;
import com.landgate.trigger.gateway.oauth.UserIdRewriter;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.trigger.gateway.transformer.AnthropicTransformer;
import com.landgate.trigger.gateway.transformer.UpstreamRequestContext;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.AnthropicApiProfile;
import com.landgate.types.gateway.GatewaySensitiveHeaderPolicy;
import com.landgate.types.gateway.MetadataUserIdParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AnthropicTransformer 单元测试 —— 验证 HTTP 请求构造只消费已解析路由，不重新决策上游端点。
 */
@DisplayName("AnthropicTransformer 测试")
class AnthropicTransformerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("构建请求时优先使用 UpstreamRoute 的 targetUrl")
    void buildRequestUsesRouteTargetUrlBeforeAccountExtraBaseUrl() {
        AnthropicTransformer transformer = new AnthropicTransformer(null, null, null, null);
        AccountEntity account = AccountEntity.builder()
                .id(1L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.API_KEY)
                .extra("{\"base_url\":\"https://extra.example.com\"}")
                .build();
        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                "{\"model\":\"claude-test\"}",
                account,
                "token-1",
                new UpstreamRoute(
                        Platform.ANTHROPIC,
                        "messages",
                        "messages",
                        EndpointKind.ANTHROPIC_MESSAGES,
                        "https://route.example.com/v1/messages",
                        false,
                        false,
                        "messages",
                        "test_route"),
                null,
                null,
                null,
                false,
                false,
                Map.of()));

        assertEquals("https://route.example.com/v1/messages", request.uri().toString());
    }

    @Test
    @DisplayName("Anthropic API Key route replaces auth and preserves safe beta header")
    void apiKeyRouteReplacesInboundAuthAndPreservesSafeBetaHeader() {
        AnthropicTransformer transformer = new AnthropicTransformer(null, null, null, null);
        AccountEntity account = AccountEntity.builder()
                .id(1L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.API_KEY)
                .build();

        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                "{\"model\":\"claude-test\"}",
                account,
                "upstream-anthropic-key",
                new UpstreamRoute(
                        Platform.ANTHROPIC,
                        "messages",
                        "messages",
                        EndpointKind.ANTHROPIC_MESSAGES,
                        "https://route.example.com/v1/messages",
                        false,
                        false,
                        "messages",
                        "test_route"),
                null,
                null,
                null,
                false,
                false,
                Map.of(
                        GatewaySensitiveHeaderPolicy.HEADER_AUTHORIZATION, "Bearer inbound-token",
                        "X-Api-Key", "inbound-api-key",
                        "X-Goog-Api-Key", "inbound-goog-key",
                        GatewaySensitiveHeaderPolicy.HEADER_COOKIE, "secret=1",
                        GatewaySensitiveHeaderPolicy.HEADER_PROXY_AUTHORIZATION, "Basic inbound-proxy",
                        "Anthropic-Beta", "interleaved-thinking-2025-05-14",
                        AnthropicApiProfile.HEADER_USER_AGENT, "claude-cli/2.1.92 (external, cli)",
                        AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_LANG, "js",
                        AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_RUNTIME_VERSION, "v24.13.0",
                        AnthropicApiProfile.HEADER_X_CLIENT_REQUEST_ID, "client-req-1"
                )));

        var headers = request.headers();
        assertEquals("upstream-anthropic-key", headers.firstValue(AnthropicApiProfile.HEADER_X_API_KEY).orElse(""));
        assertEquals(AnthropicApiProfile.ANTHROPIC_VERSION,
                headers.firstValue(AnthropicApiProfile.HEADER_ANTHROPIC_VERSION).orElse(""));
        assertEquals("interleaved-thinking-2025-05-14",
                headers.firstValue(AnthropicApiProfile.HEADER_ANTHROPIC_BETA).orElse(""));
        assertEquals("claude-cli/2.1.92 (external, cli)",
                headers.firstValue(AnthropicApiProfile.HEADER_USER_AGENT).orElse(""));
        assertEquals("js",
                headers.firstValue(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_LANG).orElse(""));
        assertEquals("v24.13.0",
                headers.firstValue(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_RUNTIME_VERSION).orElse(""));
        assertEquals("client-req-1",
                headers.firstValue(AnthropicApiProfile.HEADER_X_CLIENT_REQUEST_ID).orElse(""));
        assertEquals(1, headers.allValues(AnthropicApiProfile.HEADER_CONTENT_TYPE).size());
        assertEquals(AnthropicApiProfile.MEDIA_TYPE_JSON,
                headers.firstValue(AnthropicApiProfile.HEADER_CONTENT_TYPE).orElse(""));
        assertFalse(headers.firstValue(GatewaySensitiveHeaderPolicy.HEADER_AUTHORIZATION).isPresent());
        assertFalse(headers.firstValue(GatewaySensitiveHeaderPolicy.HEADER_X_GOOG_API_KEY).isPresent());
        assertFalse(headers.firstValue(GatewaySensitiveHeaderPolicy.HEADER_COOKIE).isPresent());
        assertFalse(headers.firstValue(GatewaySensitiveHeaderPolicy.HEADER_PROXY_AUTHORIZATION).isPresent());
    }

    @Test
    @DisplayName("Anthropic API Key route applies account model_mapping without changing other fields")
    void apiKeyRouteAppliesAccountModelMapping() throws Exception {
        AnthropicTransformer transformer = new AnthropicTransformer(null, null, null, null);
        AccountEntity account = AccountEntity.builder()
                .id(2L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.API_KEY)
                .extra("""
                        {"model_mapping":{"claude-sonnet-4-*":"claude-sonnet-4-5-20241022"}}
                        """)
                .build();

        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                """
                {
                  "model":"claude-sonnet-4-20250514",
                  "system":[{"type":"text","text":"system"}],
                  "messages":[{"role":"user","content":[{"type":"text","text":"hello"}]}],
                  "thinking":{"type":"enabled","budget_tokens":5000},
                  "max_tokens":1024
                }
                """,
                account,
                "upstream-anthropic-key",
                anthropicRoute(),
                null,
                null,
                null,
                false,
                false,
                Map.of()));

        var body = JSON.readTree(readBody(request));
        assertEquals("claude-sonnet-4-5-20241022", body.get("model").asText());
        assertEquals("system", body.get("system").get(0).get("text").asText());
        assertEquals("hello", body.get("messages").get(0).get("content").get(0).get("text").asText());
        assertEquals("enabled", body.get("thinking").get("type").asText());
        assertEquals(5000, body.get("thinking").get("budget_tokens").asInt());
        assertEquals(1024, body.get("max_tokens").asInt());
    }

    @Test
    @DisplayName("Anthropic API Key route strips configured beta tokens")
    void apiKeyRouteStripsConfiguredBetaTokens() {
        AnthropicTransformer transformer = new AnthropicTransformer(
                null,
                null,
                null,
                null,
                new AnthropicForwardingRuntimePolicyProvider(true, false, false, "redact-thinking-2026-02-12"));
        AccountEntity account = AccountEntity.builder()
                .id(3L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.API_KEY)
                .build();

        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                "{\"model\":\"claude-test\"}",
                account,
                "upstream-anthropic-key",
                anthropicRoute(),
                null,
                null,
                null,
                false,
                false,
                Map.of(AnthropicApiProfile.HEADER_ANTHROPIC_BETA,
                        "redact-thinking-2026-02-12,interleaved-thinking-2025-05-14")));

        assertEquals("interleaved-thinking-2025-05-14",
                request.headers().firstValue(AnthropicApiProfile.HEADER_ANTHROPIC_BETA).orElse(""));
    }

    @Test
    @DisplayName("Anthropic OAuth mimic route uses selected bearer and merged single beta header")
    void oauthMimicRouteUsesSelectedBearerAndMergedSingleBetaHeader() {
        AnthropicTransformer transformer = new AnthropicTransformer(
                new FingerprintService(),
                new UserIdRewriter(),
                new BillingHeaderService(),
                new OAuthMimicryService());
        AccountEntity account = AccountEntity.builder()
                .id(1L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.OAUTH)
                .extra("{}")
                .build();

        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                "{\"model\":\"claude-sonnet-4-5\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}",
                account,
                "selected-oauth-token",
                new UpstreamRoute(
                        Platform.ANTHROPIC,
                        "messages",
                        "messages",
                        EndpointKind.ANTHROPIC_MESSAGES,
                        "https://route.example.com/v1/messages",
                        false,
                        false,
                        "messages",
                        "test_route"),
                null,
                null,
                null,
                true,
                true,
                Map.of(AnthropicApiProfile.HEADER_ANTHROPIC_BETA,
                        "redact-thinking-2026-02-12,oauth-2025-04-20")));

        var headers = request.headers();
        assertEquals("Bearer selected-oauth-token",
                headers.firstValue(AnthropicApiProfile.HEADER_AUTHORIZATION).orElse(""));
        assertFalse(headers.firstValue(AnthropicApiProfile.HEADER_X_API_KEY).isPresent());
        assertEquals(1, headers.allValues(AnthropicApiProfile.HEADER_ANTHROPIC_BETA).size());
        assertEquals(AnthropicClaudeCodeProfile.fullMimicryBetaHeader() + ",redact-thinking-2026-02-12",
                headers.firstValue(AnthropicApiProfile.HEADER_ANTHROPIC_BETA).orElse(""));
    }

    @Test
    @DisplayName("Anthropic OAuth real client syncs session header from rewritten metadata user id")
    void oauthRealClientSyncsSessionHeaderFromRewrittenMetadataUserId() throws Exception {
        AnthropicTransformer transformer = new AnthropicTransformer(
                new FingerprintService(),
                new UserIdRewriter(),
                new BillingHeaderService(),
                new OAuthMimicryService());
        AccountEntity account = AccountEntity.builder()
                .id(42L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.OAUTH)
                .extra("{\"account_uuid\":\"account-selected\"}")
                .build();
        String originalSession = "session-original";
        String metadataUserId = """
                {"device_id":"device-original","account_uuid":"account-original","session_id":"%s"}
                """.formatted(originalSession).trim();

        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                anthropicBody(metadataUserId),
                account,
                "selected-oauth-token",
                anthropicRoute(),
                null,
                null,
                null,
                false,
                false,
                Map.of(
                        AnthropicApiProfile.HEADER_USER_AGENT, "claude-cli/2.1.78 (external, cli)",
                        "x-stainless-os", "Darwin",
                        "X-Stainless-Arch", "x64",
                        "x-claude-code-session-id", "client-session-before"
                )));

        var parsed = metadataUserIdFromRequest(request);
        assertNotNull(parsed);
        assertEquals("account-selected", parsed.accountUuid());
        assertNotEquals(originalSession, parsed.sessionId());
        assertEquals(parsed.sessionId(),
                request.headers().firstValue(AnthropicApiProfile.HEADER_X_CLAUDE_CODE_SESSION_ID).orElse(""));
        assertEquals("Bearer selected-oauth-token",
                request.headers().firstValue(AnthropicApiProfile.HEADER_AUTHORIZATION).orElse(""));
        assertFalse(request.headers().firstValue(AnthropicApiProfile.HEADER_X_API_KEY).isPresent());
        assertEquals("claude-cli/2.1.78 (external, cli)",
                request.headers().firstValue(AnthropicApiProfile.HEADER_USER_AGENT).orElse(""));
        assertEquals("Darwin",
                request.headers().firstValue(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_OS).orElse(""));
        assertEquals("x64",
                request.headers().firstValue(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_ARCH).orElse(""));
        assertEquals(AnthropicClaudeCodeProfile.DEFAULT_MIMICRY_HEADERS.get(
                        AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_LANG),
                request.headers().firstValue(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_LANG).orElse(""));
    }

    @Test
    @DisplayName("Anthropic OAuth real client does not inject session header when inbound header is absent")
    void oauthRealClientDoesNotInjectSessionHeaderWhenInboundHeaderAbsent() {
        AnthropicTransformer transformer = new AnthropicTransformer(
                new FingerprintService(),
                new UserIdRewriter(),
                new BillingHeaderService(),
                new OAuthMimicryService());
        AccountEntity account = AccountEntity.builder()
                .id(43L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.OAUTH)
                .extra("{\"account_uuid\":\"account-selected\"}")
                .build();

        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                anthropicBody("""
                        {"device_id":"device-original","account_uuid":"account-original","session_id":"session-original"}
                        """.trim()),
                account,
                "selected-oauth-token",
                anthropicRoute(),
                null,
                null,
                null,
                false,
                false,
                Map.of(AnthropicApiProfile.HEADER_USER_AGENT, AnthropicClaudeCodeProfile.DEFAULT_CLAUDE_CLI_USER_AGENT)));

        assertFalse(request.headers().firstValue(AnthropicApiProfile.HEADER_X_CLAUDE_CODE_SESSION_ID).isPresent());
    }

    @Test
    @DisplayName("Anthropic OAuth real client honors metadata passthrough and fingerprint switches")
    void oauthRealClientHonorsMetadataPassthroughAndFingerprintSwitches() throws Exception {
        AnthropicTransformer transformer = new AnthropicTransformer(
                new FingerprintService(),
                new UserIdRewriter(),
                new BillingHeaderService(),
                new OAuthMimicryService(),
                new AnthropicForwardingRuntimePolicyProvider(false, true, false, ""));
        AccountEntity account = AccountEntity.builder()
                .id(45L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.OAUTH)
                .extra("{\"account_uuid\":\"account-selected\"}")
                .build();
        String metadataUserId = """
                {"device_id":"device-original","account_uuid":"account-original","session_id":"session-original"}
                """.trim();

        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                anthropicBody(metadataUserId),
                account,
                "selected-oauth-token",
                anthropicRoute(),
                null,
                null,
                null,
                false,
                false,
                Map.of(
                        AnthropicApiProfile.HEADER_USER_AGENT, "claude-cli/2.1.78 (external, cli)",
                        "x-stainless-os", "Darwin",
                        "X-Stainless-Arch", "x64",
                        "x-claude-code-session-id", "client-session-before"
                )));

        var body = JSON.readTree(readBody(request));
        assertEquals(metadataUserId, body.get("metadata").get("user_id").asText());
        assertEquals("session-original",
                request.headers().firstValue(AnthropicApiProfile.HEADER_X_CLAUDE_CODE_SESSION_ID).orElse(""));
        assertEquals("Bearer selected-oauth-token",
                request.headers().firstValue(AnthropicApiProfile.HEADER_AUTHORIZATION).orElse(""));
    }

    @Test
    @DisplayName("Anthropic OAuth route signs CCH when forwarding policy enables it")
    void oauthRouteSignsCchWhenEnabled() throws Exception {
        AnthropicTransformer transformer = new AnthropicTransformer(
                new FingerprintService(),
                new UserIdRewriter(),
                new BillingHeaderService(),
                new OAuthMimicryService(),
                new AnthropicForwardingRuntimePolicyProvider(true, true, true, ""));
        AccountEntity account = AccountEntity.builder()
                .id(46L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.OAUTH)
                .extra("{}")
                .build();

        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                """
                {"model":"claude-sonnet-4-5","system":[{"type":"text","text":"x-anthropic-billing-header: cc_version=2.1.92.abcde; cc_entrypoint=cli; cch=00000;"}],"messages":[{"role":"user","content":"hi"}]}
                """.trim(),
                account,
                "selected-oauth-token",
                anthropicRoute(),
                null,
                null,
                null,
                false,
                false,
                Map.of()));

        String body = readBody(request);
        assertTrue(body.contains("cch="));
        assertFalse(body.contains("cch=00000;"));
    }

    @Test
    @DisplayName("Anthropic OAuth route applies optional cache_control ttl=1h injection")
    void oauthRouteAppliesOptionalCacheControlTtl1hInjection() throws Exception {
        OAuthMimicryService mimicryService = new OAuthMimicryService();
        ReflectionTestUtils.setField(mimicryService, "cacheTTL1hInjection", true);
        AnthropicTransformer transformer = new AnthropicTransformer(
                new FingerprintService(),
                new UserIdRewriter(),
                new BillingHeaderService(),
                mimicryService);
        AccountEntity account = AccountEntity.builder()
                .id(44L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.OAUTH)
                .extra("{}")
                .build();

        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                """
                {"model":"claude-sonnet-4-5","messages":[{"role":"user","content":[{"type":"text","text":"hi","cache_control":{"type":"ephemeral","ttl":"5m"}}]}]}
                """.trim(),
                account,
                "selected-oauth-token",
                anthropicRoute(),
                null,
                null,
                null,
                false,
                true,
                Map.of()));

        var body = JSON.readTree(readBody(request));
        assertEquals(AnthropicClaudeCodeProfile.CACHE_CONTROL_TTL_1H,
                body.get("messages").get(0).get("content").get(0).get("cache_control").get("ttl").asText());
    }

    private static UpstreamRoute anthropicRoute() {
        return new UpstreamRoute(
                Platform.ANTHROPIC,
                "messages",
                "messages",
                EndpointKind.ANTHROPIC_MESSAGES,
                "https://route.example.com/v1/messages",
                false,
                false,
                "messages",
                "test_route");
    }

    private static String anthropicBody(String metadataUserId) {
        try {
            return """
                    {"model":"claude-sonnet-4-5","metadata":{"user_id":%s},"messages":[{"role":"user","content":"Hello"}]}
                    """.formatted(JSON.writeValueAsString(metadataUserId)).trim();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static MetadataUserIdParser.ParsedMetadataUserId metadataUserIdFromRequest(HttpRequest request)
            throws Exception {
        String body = readBody(request);
        String userId = JSON.readTree(body).get("metadata").get("user_id").asText();
        return MetadataUserIdParser.parse(userId);
    }

    private static String readBody(HttpRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        assertTrue(done.await(1, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
        return output.toString(StandardCharsets.UTF_8);
    }
}
