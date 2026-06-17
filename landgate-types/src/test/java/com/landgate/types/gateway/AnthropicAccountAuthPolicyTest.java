package com.landgate.types.gateway;

import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Anthropic account auth policy tests")
class AnthropicAccountAuthPolicyTest {

    @Test
    @DisplayName("Anthropic platform predicate is centralized")
    void anthropicPlatformPredicate() {
        assertTrue(AnthropicAccountAuthPolicy.isAnthropicPlatform(Platform.ANTHROPIC));
        assertFalse(AnthropicAccountAuthPolicy.isAnthropicPlatform(Platform.OPENAI));
    }

    @Test
    @DisplayName("OAuth and setup-token account types share Anthropic OAuth compatibility")
    void oauthOrSetupTokenTypes() {
        assertTrue(AnthropicAccountAuthPolicy.isOAuthOrSetupTokenType(AccountType.OAUTH));
        assertTrue(AnthropicAccountAuthPolicy.isOAuthOrSetupTokenType(AccountType.SETUP_TOKEN));
        assertFalse(AnthropicAccountAuthPolicy.isOAuthOrSetupTokenType(AccountType.API_KEY));
    }

    @Test
    @DisplayName("Anthropic OAuth/setup-token route predicate includes platform")
    void anthropicOauthOrSetupToken() {
        assertTrue(AnthropicAccountAuthPolicy.isAnthropicOAuthOrSetupToken(
                Platform.ANTHROPIC, AccountType.OAUTH));
        assertTrue(AnthropicAccountAuthPolicy.isAnthropicOAuthOrSetupToken(
                Platform.ANTHROPIC, AccountType.SETUP_TOKEN));
        assertFalse(AnthropicAccountAuthPolicy.isAnthropicOAuthOrSetupToken(
                Platform.OPENAI, AccountType.OAUTH));
        assertFalse(AnthropicAccountAuthPolicy.isAnthropicOAuthOrSetupToken(
                Platform.ANTHROPIC, AccountType.API_KEY));
    }

    @Test
    @DisplayName("Claude Code mimicry applies only to non-Claude-Code clients on Anthropic OAuth routes")
    void shouldMimicClaudeCode() {
        assertTrue(AnthropicAccountAuthPolicy.shouldMimicClaudeCode(
                Platform.ANTHROPIC, AccountType.OAUTH, false));
        assertTrue(AnthropicAccountAuthPolicy.shouldMimicClaudeCode(
                Platform.ANTHROPIC, AccountType.SETUP_TOKEN, false));
        assertFalse(AnthropicAccountAuthPolicy.shouldMimicClaudeCode(
                Platform.ANTHROPIC, AccountType.OAUTH, true));
        assertFalse(AnthropicAccountAuthPolicy.shouldMimicClaudeCode(
                Platform.OPENAI, AccountType.OAUTH, false));
        assertFalse(AnthropicAccountAuthPolicy.shouldMimicClaudeCode(
                Platform.ANTHROPIC, AccountType.API_KEY, false));
    }
}
