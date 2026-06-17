package com.landgate.types.gateway;

import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GatewayRouteCompatibilityPolicy tests")
class GatewayRouteCompatibilityPolicyTest {

    @Test
    @DisplayName("OpenAI encrypted reasoning retry only applies to OpenAI Responses upstream")
    void openAiResponsesRetryRequiresOpenAiResponsesUpstream() {
        assertTrue(GatewayRouteCompatibilityPolicy.isOpenAiResponsesRetryEligible(
                Platform.OPENAI, Platform.OPENAI, GatewayProtocolFormat.RESPONSES.id()));

        assertFalse(GatewayRouteCompatibilityPolicy.isOpenAiResponsesRetryEligible(
                Platform.ANTHROPIC, Platform.OPENAI, GatewayProtocolFormat.RESPONSES.id()));
        assertFalse(GatewayRouteCompatibilityPolicy.isOpenAiResponsesRetryEligible(
                Platform.OPENAI, Platform.ANTHROPIC, GatewayProtocolFormat.RESPONSES.id()));
        assertFalse(GatewayRouteCompatibilityPolicy.isOpenAiResponsesRetryEligible(
                Platform.OPENAI, Platform.OPENAI, GatewayProtocolFormat.CHAT_COMPLETIONS.id()));
    }

    @Test
    @DisplayName("Anthropic thinking retry only applies to Anthropic Messages upstream")
    void anthropicThinkingRetryRequiresAnthropicMessagesUpstream() {
        assertTrue(GatewayRouteCompatibilityPolicy.isAnthropicMessagesRetryEligible(
                Platform.ANTHROPIC, Platform.ANTHROPIC, GatewayProtocolFormat.MESSAGES.id()));

        assertFalse(GatewayRouteCompatibilityPolicy.isAnthropicMessagesRetryEligible(
                Platform.OPENAI, Platform.ANTHROPIC, GatewayProtocolFormat.MESSAGES.id()));
        assertFalse(GatewayRouteCompatibilityPolicy.isAnthropicMessagesRetryEligible(
                Platform.ANTHROPIC, Platform.OPENAI, GatewayProtocolFormat.MESSAGES.id()));
        assertFalse(GatewayRouteCompatibilityPolicy.isAnthropicMessagesRetryEligible(
                Platform.ANTHROPIC, Platform.ANTHROPIC, GatewayProtocolFormat.RESPONSES.id()));
    }

    @Test
    @DisplayName("Anthropic Messages compat is Messages client to OpenAI Responses upstream")
    void openAiAnthropicMessagesCompatRequiresMessagesToOpenAiResponses() {
        assertTrue(GatewayRouteCompatibilityPolicy.isOpenAiAnthropicMessagesCompat(
                Platform.OPENAI, GatewayProtocolFormat.MESSAGES.id(), GatewayProtocolFormat.RESPONSES.id()));

        assertFalse(GatewayRouteCompatibilityPolicy.isOpenAiAnthropicMessagesCompat(
                Platform.OPENAI, GatewayProtocolFormat.RESPONSES.id(), GatewayProtocolFormat.RESPONSES.id()));
        assertFalse(GatewayRouteCompatibilityPolicy.isOpenAiAnthropicMessagesCompat(
                Platform.OPENAI, GatewayProtocolFormat.MESSAGES.id(), GatewayProtocolFormat.CHAT_COMPLETIONS.id()));
        assertFalse(GatewayRouteCompatibilityPolicy.isOpenAiAnthropicMessagesCompat(
                Platform.ANTHROPIC, GatewayProtocolFormat.MESSAGES.id(), GatewayProtocolFormat.RESPONSES.id()));
    }

    @Test
    @DisplayName("Anthropic client platform predicate is centralized")
    void anthropicClientPlatformPredicate() {
        assertTrue(GatewayRouteCompatibilityPolicy.isAnthropicClientPlatform(Platform.ANTHROPIC));
        assertFalse(GatewayRouteCompatibilityPolicy.isAnthropicClientPlatform(Platform.OPENAI));
    }

    @Test
    @DisplayName("Chat Completions Codex compat requires OpenAI OAuth Chat to Codex Responses")
    void chatCompletionsCodexCompatRequiresOpenAiOauthCodexResponsesRoute() {
        assertTrue(GatewayRouteCompatibilityPolicy.isOpenAiOAuthChatCompletionsToCodexResponsesCompat(
                Platform.OPENAI,
                AccountType.OAUTH,
                Platform.OPENAI,
                true,
                GatewayProtocolFormat.CHAT_COMPLETIONS.id(),
                GatewayProtocolFormat.RESPONSES.id()));

        assertFalse(GatewayRouteCompatibilityPolicy.isOpenAiOAuthChatCompletionsToCodexResponsesCompat(
                Platform.OPENAI,
                AccountType.API_KEY,
                Platform.OPENAI,
                true,
                GatewayProtocolFormat.CHAT_COMPLETIONS.id(),
                GatewayProtocolFormat.RESPONSES.id()));
        assertFalse(GatewayRouteCompatibilityPolicy.isOpenAiOAuthChatCompletionsToCodexResponsesCompat(
                Platform.OPENAI,
                AccountType.OAUTH,
                Platform.OPENAI,
                false,
                GatewayProtocolFormat.CHAT_COMPLETIONS.id(),
                GatewayProtocolFormat.RESPONSES.id()));
        assertFalse(GatewayRouteCompatibilityPolicy.isOpenAiOAuthChatCompletionsToCodexResponsesCompat(
                Platform.OPENAI,
                AccountType.OAUTH,
                Platform.OPENAI,
                true,
                GatewayProtocolFormat.MESSAGES.id(),
                GatewayProtocolFormat.RESPONSES.id()));
    }
}
