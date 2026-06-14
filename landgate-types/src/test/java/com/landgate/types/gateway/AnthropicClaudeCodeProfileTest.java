package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AnthropicClaudeCodeProfile 测试")
class AnthropicClaudeCodeProfileTest {

    @Test
    @DisplayName("Claude Code OAuth mimicry beta 集合对齐 Sub2API")
    void mimicryBetasMatchSub2ApiProfile() {
        assertEquals("claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14,"
                        + "prompt-caching-scope-2026-01-05,effort-2025-11-24,"
                        + "context-management-2025-06-27,extended-cache-ttl-2025-04-11",
                AnthropicClaudeCodeProfile.fullMimicryBetaHeader());
        assertEquals("oauth-2025-04-20,interleaved-thinking-2025-05-14",
                AnthropicClaudeCodeProfile.haikuMimicryBetaHeader());
    }

    @Test
    @DisplayName("Claude Code 默认头和版本保持集中")
    void defaultHeadersAreCentralized() {
        assertEquals("2.1.92", AnthropicClaudeCodeProfile.CLI_CURRENT_VERSION);
        assertEquals("claude-cli/2.1.92 (external, cli)",
                AnthropicClaudeCodeProfile.DEFAULT_CLAUDE_CLI_USER_AGENT);
        assertEquals("fast-mode-2026-02-01", AnthropicClaudeCodeProfile.BETA_FAST_MODE);
        assertEquals(AnthropicClaudeCodeProfile.DEFAULT_CLAUDE_CLI_USER_AGENT,
                AnthropicClaudeCodeProfile.DEFAULT_MIMICRY_HEADERS.get(AnthropicApiProfile.HEADER_USER_AGENT));
        assertEquals("cli", AnthropicClaudeCodeProfile.DEFAULT_MIMICRY_HEADERS.get(AnthropicApiProfile.HEADER_X_APP));
        assertEquals("application/json",
                AnthropicClaudeCodeProfile.DEFAULT_MIMICRY_HEADERS.get(AnthropicApiProfile.HEADER_ACCEPT));
        assertEquals("5m", AnthropicClaudeCodeProfile.DEFAULT_CACHE_CONTROL_TTL);
        assertEquals("1h", AnthropicClaudeCodeProfile.CACHE_CONTROL_TTL_1H);
        assertTrue(AnthropicClaudeCodeProfile.CLAUDE_CLI_UA_PATTERN.matcher("claude-cli/2.1.92").matches());
    }

    @Test
    @DisplayName("Claude Code billing 与 account extra 字段由 profile 统一承载")
    void billingAndAccountExtraFactsAreCentralized() {
        assertEquals("account_uuid", AnthropicClaudeCodeProfile.ACCOUNT_EXTRA_ACCOUNT_UUID);
        assertEquals("claude_user_id", AnthropicClaudeCodeProfile.ACCOUNT_EXTRA_CLAUDE_USER_ID);
        assertEquals("x-anthropic-billing-header: cc_version=2.1.92.abc; cc_entrypoint=cli; cch=00000;",
                AnthropicClaudeCodeProfile.billingHeaderText("2.1.92", "abc"));
        assertTrue(AnthropicClaudeCodeProfile.isBillingHeaderText(
                AnthropicClaudeCodeProfile.billingHeaderText("2.1.92", "abc")));
        assertFalse(AnthropicClaudeCodeProfile.isBillingHeaderText("ordinary system prompt"));
    }

    @Test
    @DisplayName("Claude Code 模型短名映射对齐 Sub2API")
    void modelAliasesMatchSub2ApiProfile() {
        assertEquals("claude-sonnet-4-5-20250929",
                AnthropicClaudeCodeProfile.normalizeModelId("claude-sonnet-4-5"));
        assertEquals("claude-opus-4-5-20251101",
                AnthropicClaudeCodeProfile.normalizeModelId("claude-opus-4-5"));
        assertEquals("claude-haiku-4-5-20251001",
                AnthropicClaudeCodeProfile.normalizeModelId("claude-haiku-4-5"));
        assertEquals("claude-3-5-sonnet-latest",
                AnthropicClaudeCodeProfile.normalizeModelId("claude-3-5-sonnet-latest"));
    }

    @Test
    @DisplayName("Claude Code mimicry beta 合并保留客户端 beta 并去重")
    void mergeBetaHeaderPreservesIncomingBetas() {
        String merged = AnthropicClaudeCodeProfile.mergeBetaHeader(
                AnthropicClaudeCodeProfile.HAIKU_MIMICRY_BETAS,
                "foo, oauth-2025-04-20,bar, foo");

        assertEquals("oauth-2025-04-20,interleaved-thinking-2025-05-14,foo,bar", merged);
    }

    @Test
    @DisplayName("真实 Claude Code beta 透传时仅补齐 oauth beta")
    void ensureOAuthBetaHeaderPreservesClientOrder() {
        assertEquals("claude-code-20250219,oauth-2025-04-20,foo",
                AnthropicClaudeCodeProfile.ensureOAuthBetaHeader("claude-sonnet-4-5",
                        "claude-code-20250219,foo"));
        assertEquals("oauth-2025-04-20,foo",
                AnthropicClaudeCodeProfile.ensureOAuthBetaHeader("claude-sonnet-4-5", "foo"));
        assertEquals("oauth-2025-04-20,interleaved-thinking-2025-05-14",
                AnthropicClaudeCodeProfile.ensureOAuthBetaHeader("claude-haiku-4-5", ""));
    }

    @Test
    @DisplayName("count_tokens OAuth beta 集合补齐 token-counting")
    void countTokensOAuthBetaHeaderIncludesTokenCounting() {
        assertEquals("claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14,"
                        + "token-counting-2024-11-01",
                AnthropicClaudeCodeProfile.countTokensBetaHeader());
        assertEquals("oauth-2025-04-20,foo,token-counting-2024-11-01",
                AnthropicClaudeCodeProfile.ensureCountTokensOAuthBetaHeader(
                        "claude-sonnet-4-5", "foo"));
        assertTrue(AnthropicClaudeCodeProfile.fullMimicryCountTokensBetas()
                .contains(AnthropicClaudeCodeProfile.BETA_TOKEN_COUNTING));
    }

    @Test
    @DisplayName("count_tokens beta drop-set 过滤对齐 Sub2API")
    void countTokensBetaDropSetFiltersMergedHeaders() {
        Set<String> dropped = Set.of(AnthropicClaudeCodeProfile.BETA_TOKEN_COUNTING, "foo");

        assertEquals("claude-code-20250219,oauth-2025-04-20,bar",
                AnthropicClaudeCodeProfile.mergeBetaHeader(
                        java.util.List.of(AnthropicClaudeCodeProfile.BETA_CLAUDE_CODE,
                                AnthropicClaudeCodeProfile.BETA_OAUTH,
                                AnthropicClaudeCodeProfile.BETA_TOKEN_COUNTING),
                        "foo,bar",
                        dropped));
        assertEquals("oauth-2025-04-20,bar",
                AnthropicClaudeCodeProfile.ensureCountTokensOAuthBetaHeader(
                        "claude-sonnet-4-5", "foo,bar", dropped));
        assertEquals("bar",
                AnthropicClaudeCodeProfile.stripBetaTokens("foo,bar,foo", dropped));
    }

    @Test
    @DisplayName("count_tokens runtime policy 默认值对齐 Sub2API")
    void countTokensRuntimePolicyDefaultsMatchSub2Api() {
        AnthropicCountTokensRuntimePolicy policy = AnthropicCountTokensRuntimePolicy.defaults();

        assertTrue(policy.fingerprintUnification());
        assertFalse(policy.metadataPassthrough());
        assertFalse(policy.cchSigning());
        assertTrue(policy.betaDropTokens().isEmpty());
        assertEquals(Set.of("foo", "bar"),
                AnthropicCountTokensRuntimePolicy.parseDropTokens(" foo,bar,foo, "));
    }
}
