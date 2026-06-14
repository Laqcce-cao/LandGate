package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.landgate.types.enums.AccountType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenAiFastPolicy 测试")
class OpenAiFastPolicyTest {

    @Test
    @DisplayName("默认 OpenAI fast policy 对齐 Sub2API")
    void defaultPolicyFiltersPriorityOnly() {
        assertEquals("filter", OpenAiFastPolicy.defaultActionForNormalizedTier("priority"));
        assertEquals("pass", OpenAiFastPolicy.defaultActionForNormalizedTier("flex"));
        assertEquals("pass", OpenAiFastPolicy.defaultActionForNormalizedTier("auto"));
        assertEquals("pass", OpenAiFastPolicy.defaultActionForNormalizedTier("default"));
        assertEquals("pass", OpenAiFastPolicy.defaultActionForNormalizedTier("scale"));

        assertTrue(OpenAiFastPolicy.defaultShouldFilter("priority"));
        assertFalse(OpenAiFastPolicy.defaultShouldFilter("flex"));
    }

    @Test
    @DisplayName("规则匹配支持 scope、tier、model whitelist 和 fallback")
    void evaluatesSub2ApiStyleRules() {
        OpenAiFastPolicy.Settings settings = new OpenAiFastPolicy.Settings(List.of(
                new OpenAiFastPolicy.Rule(
                        "priority",
                        "block",
                        "oauth",
                        "blocked",
                        List.of("gpt-5.5", "gpt-5.5*"),
                        "filter",
                        ""),
                new OpenAiFastPolicy.Rule(
                        "all",
                        "filter",
                        "all",
                        "",
                        List.of(),
                        "pass",
                        "")));

        OpenAiFastPolicy.Decision oauthExact = OpenAiFastPolicy.evaluate(
                settings, AccountType.OAUTH, "gpt-5.5", "priority");
        assertTrue(oauthExact.blocks());
        assertEquals("blocked", oauthExact.message());

        OpenAiFastPolicy.Decision oauthFallback = OpenAiFastPolicy.evaluate(
                settings, AccountType.OAUTH, "gpt-4", "priority");
        assertTrue(oauthFallback.filters());

        OpenAiFastPolicy.Decision apiKeyFallsThroughToSecondRule = OpenAiFastPolicy.evaluate(
                settings, AccountType.API_KEY, "gpt-5.5", "flex");
        assertTrue(apiKeyFallsThroughToSecondRule.filters());
    }

    @Test
    @DisplayName("model whitelist 支持精确、前缀通配和 *")
    void modelWhitelistMatchesSub2ApiPatterns() {
        assertTrue(OpenAiFastPolicy.matchesModelWhitelist("gpt-5.5", List.of("gpt-5.5")));
        assertTrue(OpenAiFastPolicy.matchesModelWhitelist("gpt-5.5-mini", List.of("gpt-5.5*")));
        assertTrue(OpenAiFastPolicy.matchesModelWhitelist("anything", List.of("*")));
        assertFalse(OpenAiFastPolicy.matchesModelWhitelist("gpt-4", List.of("gpt-5.5*")));
    }
}
