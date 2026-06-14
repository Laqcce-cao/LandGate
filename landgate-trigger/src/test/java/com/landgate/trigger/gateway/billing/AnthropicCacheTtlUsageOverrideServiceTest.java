package com.landgate.trigger.gateway.billing;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.GatewayCacheTtlPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Anthropic cache TTL usage override service")
class AnthropicCacheTtlUsageOverrideServiceTest {

    private final AnthropicCacheTtlUsageOverrideService service = new AnthropicCacheTtlUsageOverrideService();

    @Test
    @DisplayName("account override has priority over global 1h request injection billing fallback")
    void accountOverrideHasPriority() {
        AccountEntity account = anthropicOAuth("""
                {"cache_ttl_override_enabled":true,"cache_ttl_override_target":"1h"}
                """);

        assertEquals(GatewayCacheTtlPolicy.TARGET_1H, service.resolveOverrideTarget(account, true));
    }

    @Test
    @DisplayName("global 1h request injection bills usage back to 5m for Anthropic OAuth")
    void globalInjectionDefaultsUsageBackTo5m() {
        AccountEntity account = anthropicOAuth("{}");
        UsageTokens usage = UsageTokens.builder()
                .cacheCreation5mTokens(2)
                .cacheCreation1hTokens(8)
                .build();

        assertEquals(GatewayCacheTtlPolicy.TARGET_5M, service.resolveOverrideTarget(account, true));
        assertTrue(usage.applyCacheTtlOverride(service.resolveOverrideTarget(account, true)));
        assertEquals(10, usage.getCacheCreation5mTokens());
        assertEquals(0, usage.getCacheCreation1hTokens());
    }

    @Test
    @DisplayName("disabled account override and disabled global setting leave usage untouched")
    void noOverrideWhenDisabled() {
        AccountEntity account = anthropicOAuth("""
                {"cache_ttl_override_enabled":false,"cache_ttl_override_target":"1h"}
                """);

        assertNull(service.resolveOverrideTarget(account, false));
    }

    @Test
    @DisplayName("OpenAI accounts are not affected")
    void openAiAccountIgnored() {
        AccountEntity account = AccountEntity.builder()
                .id(1L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .extra("""
                        {"cache_ttl_override_enabled":true,"cache_ttl_override_target":"1h"}
                        """)
                .build();

        assertNull(service.resolveOverrideTarget(account, true));
    }

    @Test
    @DisplayName("applyIfNeeded ignores null usage")
    void nullUsageIgnored() {
        assertFalse(service.applyIfNeeded(null, anthropicOAuth("{}")));
    }

    @Test
    @DisplayName("non-streaming body cache_creation split is rewritten when usage override changes buckets")
    void nonStreamingBodyCacheCreationSplitIsRewritten() throws Exception {
        AccountEntity account = anthropicOAuth("""
                {"cache_ttl_override_enabled":true,"cache_ttl_override_target":"1h"}
                """);
        UsageTokens usage = UsageTokens.builder()
                .cacheCreation5mTokens(3)
                .cacheCreation1hTokens(4)
                .build();

        var result = service.applyToNonStreamingBody("""
                {"usage":{"cache_creation":{"ephemeral_5m_input_tokens":3,"ephemeral_1h_input_tokens":4}}}
                """, usage, account);

        assertTrue(result.changed());
        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.body());
        assertEquals(0, root.path(AnthropicMessagesBodyPolicy.FIELD_USAGE)
                .path(AnthropicMessagesBodyPolicy.FIELD_CACHE_CREATION)
                .path(AnthropicMessagesBodyPolicy.FIELD_EPHEMERAL_5M_INPUT_TOKENS).asInt());
        assertEquals(7, root.path(AnthropicMessagesBodyPolicy.FIELD_USAGE)
                .path(AnthropicMessagesBodyPolicy.FIELD_CACHE_CREATION)
                .path(AnthropicMessagesBodyPolicy.FIELD_EPHEMERAL_1H_INPUT_TOKENS).asInt());
    }

    private static AccountEntity anthropicOAuth(String extra) {
        return AccountEntity.builder()
                .id(1L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.OAUTH)
                .extra(extra)
                .build();
    }
}
