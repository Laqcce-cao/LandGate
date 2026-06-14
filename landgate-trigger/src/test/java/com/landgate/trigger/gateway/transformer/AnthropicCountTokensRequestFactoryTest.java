package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.oauth.FingerprintService;
import com.landgate.trigger.gateway.oauth.OAuthMimicryService;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.AnthropicApiProfile;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import com.landgate.types.gateway.GatewaySensitiveHeaderPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AnthropicCountTokensRequestFactory")
class AnthropicCountTokensRequestFactoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AnthropicCountTokensRequestFactory factory =
            new AnthropicCountTokensRequestFactory(new OAuthMimicryService(), new FingerprintService());

    @Test
    @DisplayName("API key count_tokens passthrough strips configured beta tokens")
    void apiKeyCountTokensStripsConfiguredBetaTokens() {
        HttpRequest request = factory.build(
                account(AccountType.API_KEY),
                "sk-ant-test",
                body(),
                Map.of(
                        GatewaySensitiveHeaderPolicy.HEADER_AUTHORIZATION, "Bearer inbound",
                        "X-Api-Key", "inbound-api-key",
                        AnthropicApiProfile.HEADER_ANTHROPIC_BETA,
                        "foo," + AnthropicClaudeCodeProfile.BETA_TOKEN_COUNTING,
                        AnthropicApiProfile.HEADER_USER_AGENT, "claude-cli/2.1.92 (external, cli)",
                        AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_LANG, "js",
                        AnthropicApiProfile.HEADER_X_CLIENT_REQUEST_ID, "client-req-ct"),
                new AnthropicCountTokensRequestFactory.Options(
                        "claude-sonnet-4-5",
                        false,
                        null,
                        Set.of(AnthropicClaudeCodeProfile.BETA_TOKEN_COUNTING)));

        Optional<String> beta = request.headers().firstValue(AnthropicApiProfile.HEADER_ANTHROPIC_BETA);
        assertTrue(beta.isPresent());
        assertTrue(beta.get().contains("foo"));
        assertFalse(beta.get().contains(AnthropicClaudeCodeProfile.BETA_TOKEN_COUNTING));
        assertEquals(Optional.of("sk-ant-test"), request.headers().firstValue(AnthropicApiProfile.HEADER_X_API_KEY));
        assertFalse(request.headers().firstValue(GatewaySensitiveHeaderPolicy.HEADER_AUTHORIZATION).isPresent());
        assertEquals(Optional.of("claude-cli/2.1.92 (external, cli)"),
                request.headers().firstValue(AnthropicApiProfile.HEADER_USER_AGENT));
        assertEquals(Optional.of("js"),
                request.headers().firstValue(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_LANG));
        assertEquals(Optional.of("client-req-ct"),
                request.headers().firstValue(AnthropicApiProfile.HEADER_X_CLIENT_REQUEST_ID));
        assertEquals(1, request.headers().allValues(AnthropicApiProfile.HEADER_CONTENT_TYPE).size());
        assertEquals(Optional.of(AnthropicApiProfile.MEDIA_TYPE_JSON),
                request.headers().firstValue(AnthropicApiProfile.HEADER_CONTENT_TYPE));
    }

    @Test
    @DisplayName("OAuth count_tokens mimicry strips configured beta tokens after merging required betas")
    void oauthMimicryCountTokensStripsConfiguredBetaTokens() {
        HttpRequest request = factory.build(
                account(AccountType.OAUTH),
                "oauth-token",
                body(),
                Map.of(AnthropicApiProfile.HEADER_ANTHROPIC_BETA,
                        "foo," + AnthropicClaudeCodeProfile.BETA_TOKEN_COUNTING),
                new AnthropicCountTokensRequestFactory.Options(
                        "claude-sonnet-4-5",
                        true,
                        null,
                        Set.of(AnthropicClaudeCodeProfile.BETA_TOKEN_COUNTING)));

        String beta = request.headers().firstValue(AnthropicApiProfile.HEADER_ANTHROPIC_BETA).orElse("");
        assertTrue(beta.contains(AnthropicClaudeCodeProfile.BETA_CLAUDE_CODE));
        assertTrue(beta.contains(AnthropicClaudeCodeProfile.BETA_OAUTH));
        assertTrue(beta.contains("foo"));
        assertFalse(beta.contains(AnthropicClaudeCodeProfile.BETA_TOKEN_COUNTING));
    }

    @Test
    @DisplayName("OAuth count_tokens normal mode strips configured beta tokens after token-counting ensure")
    void oauthNormalCountTokensStripsConfiguredBetaTokens() {
        HttpRequest request = factory.build(
                account(AccountType.OAUTH),
                "oauth-token",
                body(),
                Map.of(AnthropicApiProfile.HEADER_ANTHROPIC_BETA,
                        "foo," + AnthropicClaudeCodeProfile.BETA_TOKEN_COUNTING),
                new AnthropicCountTokensRequestFactory.Options(
                        "claude-sonnet-4-5",
                        false,
                        null,
                        Set.of(AnthropicClaudeCodeProfile.BETA_TOKEN_COUNTING)));

        String beta = request.headers().firstValue(AnthropicApiProfile.HEADER_ANTHROPIC_BETA).orElse("");
        assertTrue(beta.contains(AnthropicClaudeCodeProfile.BETA_OAUTH));
        assertTrue(beta.contains("foo"));
        assertFalse(beta.contains(AnthropicClaudeCodeProfile.BETA_TOKEN_COUNTING));
    }

    @Test
    @DisplayName("count_tokens syncs X-Claude-Code-Session-Id from final metadata.user_id")
    void countTokensSyncsClaudeCodeSessionIdFromMetadataUserId() {
        HttpRequest request = factory.build(
                account(AccountType.OAUTH),
                "oauth-token",
                bodyWithMetadataUserId("33333333-3333-4333-8333-333333333333"),
                Map.of(AnthropicApiProfile.HEADER_X_CLAUDE_CODE_SESSION_ID,
                        "11111111-1111-4111-8111-111111111111"),
                new AnthropicCountTokensRequestFactory.Options(
                        "claude-sonnet-4-5",
                        false,
                        null,
                        Set.of()));

        assertEquals(Optional.of("33333333-3333-4333-8333-333333333333"),
                request.headers().firstValue(AnthropicApiProfile.HEADER_X_CLAUDE_CODE_SESSION_ID));
    }

    @Test
    @DisplayName("API key count_tokens applies account model_mapping without changing other fields")
    void apiKeyCountTokensAppliesAccountModelMapping() throws Exception {
        HttpRequest request = factory.build(
                account(AccountType.API_KEY, """
                        {"model_mapping":{"claude-sonnet-4-*":"claude-sonnet-4-5-20241022"}}
                        """),
                "sk-ant-test",
                """
                {
                  "model":"claude-sonnet-4-20250514",
                  "system":[{"type":"text","text":"system"}],
                  "messages":[{"role":"user","content":[{"type":"text","text":"hello"}]}],
                  "thinking":{"type":"enabled","budget_tokens":5000},
                  "max_tokens":1024
                }
                """,
                Map.of(),
                new AnthropicCountTokensRequestFactory.Options(
                        "claude-sonnet-4-20250514",
                        false,
                        null,
                        Set.of()));

        var body = JSON.readTree(readBody(request));
        assertEquals("claude-sonnet-4-5-20241022", body.get("model").asText());
        assertEquals("system", body.get("system").get(0).get("text").asText());
        assertEquals("hello", body.get("messages").get(0).get("content").get(0).get("text").asText());
        assertEquals("enabled", body.get("thinking").get("type").asText());
        assertEquals(5000, body.get("thinking").get("budget_tokens").asInt());
        assertEquals(1024, body.get("max_tokens").asInt());
    }

    private static AccountEntity account(AccountType type) {
        return account(type, null);
    }

    private static AccountEntity account(AccountType type, String extra) {
        return AccountEntity.builder()
                .id(10L)
                .platform(Platform.ANTHROPIC)
                .type(type)
                .extra(extra)
                .build();
    }

    private static String body() {
        return "{\"model\":\"claude-sonnet-4-5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    }

    private static String bodyWithMetadataUserId(String sessionId) {
        return "{\"model\":\"claude-sonnet-4-5\","
                + "\"metadata\":{\"user_id\":\"{\\\"device_id\\\":\\\""
                + "a".repeat(64)
                + "\\\",\\\"account_uuid\\\":\\\"\\\",\\\"session_id\\\":\\\""
                + sessionId
                + "\\\"}\"},"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
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
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
