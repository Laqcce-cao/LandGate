package com.landgate.types.gateway;

import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AnthropicModelMappingPolicy")
class AnthropicModelMappingPolicyTest {

    @Test
    @DisplayName("model_mapping supports exact and longest suffix-wildcard matches")
    void modelMappingSupportsExactAndLongestWildcardMatches() {
        AnthropicModelMappingPolicy.MappedModel missing =
                AnthropicModelMappingPolicy.resolveMappedModel(
                        Platform.ANTHROPIC, AccountType.API_KEY, null, "claude-sonnet-4-20250514");
        assertFalse(missing.matched());
        assertEquals("claude-sonnet-4-20250514", missing.model());

        AnthropicModelMappingPolicy.MappedModel exact =
                AnthropicModelMappingPolicy.resolveMappedModel(
                        Platform.ANTHROPIC,
                        AccountType.API_KEY,
                        """
                        {"model_mapping":{
                          "claude-sonnet-4-20250514":"claude-sonnet-4-5-20241022",
                          "invalid":1
                        }}
                        """,
                        "claude-sonnet-4-20250514");
        assertTrue(exact.matched());
        assertEquals("claude-sonnet-4-5-20241022", exact.model());

        AnthropicModelMappingPolicy.MappedModel wildcard =
                AnthropicModelMappingPolicy.resolveMappedModel(
                        Platform.ANTHROPIC,
                        AccountType.API_KEY,
                        """
                        {"model_mapping":{
                          "claude-*":"fallback",
                          "claude-sonnet-4-*":"claude-sonnet-4-5-20241022",
                          "claude-sonnet-4-mini-*":"claude-sonnet-4-mini-upstream"
                        }}
                        """,
                        "claude-sonnet-4-mini-20250514");
        assertTrue(wildcard.matched());
        assertEquals("claude-sonnet-4-mini-upstream", wildcard.model());
    }

    @Test
    @DisplayName("model_mapping is only active for Anthropic API key accounts")
    void modelMappingIsOnlyActiveForAnthropicApiKeyAccounts() {
        String extra = "{\"model_mapping\":{\"claude-*\":\"mapped\"}}";

        assertFalse(AnthropicModelMappingPolicy.resolveMappedModel(
                Platform.ANTHROPIC, AccountType.OAUTH, extra, "claude-sonnet").matched());
        assertFalse(AnthropicModelMappingPolicy.resolveMappedModel(
                Platform.OPENAI, AccountType.API_KEY, extra, "claude-sonnet").matched());
        assertFalse(AnthropicModelMappingPolicy.resolveMappedModel(
                Platform.ANTHROPIC, AccountType.API_KEY, extra, "").matched());
    }
}
