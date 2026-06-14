package com.landgate.trigger.gateway.counttokens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.oauth.BillingHeaderService;
import com.landgate.trigger.gateway.oauth.FingerprintService;
import com.landgate.trigger.gateway.oauth.OAuthMimicryService;
import com.landgate.trigger.gateway.oauth.UserIdRewriter;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.AnthropicForwardingRuntimePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AnthropicCountTokensOAuthNormalizer")
class AnthropicCountTokensOAuthNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AnthropicCountTokensOAuthNormalizer normalizer =
            new AnthropicCountTokensOAuthNormalizer(
                    new FingerprintService(),
                    new UserIdRewriter(),
                    new BillingHeaderService(),
                    new OAuthMimicryService());

    @Test
    @DisplayName("mimicry normalizes OAuth count_tokens body like Sub2API")
    void mimicryNormalizesOAuthCountTokensBody() throws Exception {
        AccountEntity account = AccountEntity.builder()
                .id(1L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.OAUTH)
                .build();
        String body = """
                {
                  "model":"claude-sonnet-4-5",
                  "system":[{"type":"text","text":"system","cache_control":{"type":"ephemeral"}}],
                  "messages":[{"role":"user","content":"hi"}],
                  "tools":[{"type":"custom","name":"bash","description":"run","input_schema":{"type":"object"}}]
                }""";

        var result = normalizer.normalize(account, body, "claude-sonnet-4-5", true,
                Map.of("User-Agent", "curl/8.0"));
        JsonNode root = JSON.readTree(result.body());

        assertEquals("claude-sonnet-4-5-20250929", root.path("model").asText());
        assertEquals(1.0, root.path("temperature").asDouble());
        assertEquals(128000, root.path("max_tokens").asInt());
        assertFalse(root.path("system").get(0).has("cache_control"));
        assertTrue(root.path("tools").get(0).has("cache_control"));
        assertTrue(result.mimicClaudeCode());
        assertTrue(result.fingerprint() != null);
    }

    @Test
    @DisplayName("fingerprint disabled skips header fingerprint result and billing version sync")
    void fingerprintDisabledSkipsFingerprintResultAndBillingSync() {
        AccountEntity account = oauthAccount();
        String body = """
                {
                  "model":"claude-sonnet-4-5",
                  "system":[{"type":"text","text":"x-anthropic-billing-header: cc_version=2.1.92.abc; cc_entrypoint=cli; cch=00000;"}],
                  "messages":[{"role":"user","content":"hi"}]
                }""";

        var result = normalizer.normalize(
                account,
                body,
                "claude-sonnet-4-5",
                false,
                Map.of("User-Agent", "claude-cli/2.2.0 (external, cli)"),
                new AnthropicForwardingRuntimePolicy(false, false, false, Set.of()));

        assertNull(result.fingerprint());
        assertTrue(result.body().contains("cc_version=2.1.92.abc"));
        assertFalse(result.body().contains("cc_version=2.2.0.abc"));
    }

    @Test
    @DisplayName("metadata passthrough enabled keeps client metadata.user_id unchanged")
    void metadataPassthroughKeepsClientUserId() throws Exception {
        AccountEntity account = AccountEntity.builder()
                .id(3L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.OAUTH)
                .extra("{\"account_uuid\":\"11111111-1111-1111-1111-111111111111\"}")
                .build();
        String userId = "{\"device_id\":\""
                + "a".repeat(64)
                + "\",\"account_uuid\":\"client-account\",\"session_id\":\"22222222-2222-2222-2222-222222222222\"}";
        String body = """
                {
                  "model":"claude-sonnet-4-5",
                  "metadata":{"user_id":%s},
                  "messages":[{"role":"user","content":"hi"}]
                }""".formatted(JSON.writeValueAsString(userId));

        var result = normalizer.normalize(
                account,
                body,
                "claude-sonnet-4-5",
                false,
                Map.of("User-Agent", "claude-cli/2.2.0 (external, cli)"),
                new AnthropicForwardingRuntimePolicy(true, true, false, Set.of()));

        JsonNode root = JSON.readTree(result.body());
        assertEquals(userId, root.path("metadata").path("user_id").asText());
        assertTrue(result.fingerprint() != null);
    }

    @Test
    @DisplayName("CCH signing is applied after count_tokens body normalization")
    void cchSigningReplacesBillingPlaceholder() {
        AccountEntity account = oauthAccount();
        String body = """
                {
                  "model":"claude-sonnet-4-5",
                  "system":[{"type":"text","text":"x-anthropic-billing-header: cc_version=2.1.92.abc; cc_entrypoint=cli; cch=00000;"}],
                  "messages":[{"role":"user","content":"hello world"}]
                }""";

        var result = normalizer.normalize(
                account,
                body,
                "claude-sonnet-4-5",
                false,
                Map.of("User-Agent", "claude-cli/2.1.92 (external, cli)"),
                new AnthropicForwardingRuntimePolicy(true, false, true, Set.of()));

        assertFalse(result.body().contains("cch=00000"));
        assertTrue(result.body().contains("cch="));
    }

    private static AccountEntity oauthAccount() {
        return AccountEntity.builder()
                .id(2L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.OAUTH)
                .build();
    }
}
