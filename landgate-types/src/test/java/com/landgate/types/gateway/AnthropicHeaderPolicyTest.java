package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AnthropicHeaderPolicy 测试")
class AnthropicHeaderPolicyTest {

    @Test
    @DisplayName("API Key passthrough 只允许 Anthropic 安全兼容头")
    void apiKeyPassthroughAllowlistDoesNotForwardInboundCredentials() {
        assertTrue(AnthropicHeaderPolicy.API_KEY_PASSTHROUGH_ALLOWED_HEADERS.contains(
                AnthropicHeaderPolicy.headerKey(AnthropicApiProfile.HEADER_ANTHROPIC_BETA)));
        assertTrue(AnthropicHeaderPolicy.API_KEY_PASSTHROUGH_ALLOWED_HEADERS.contains(
                AnthropicHeaderPolicy.headerKey(AnthropicApiProfile.HEADER_ACCEPT)));
        assertTrue(AnthropicHeaderPolicy.API_KEY_PASSTHROUGH_ALLOWED_HEADERS.contains(
                AnthropicHeaderPolicy.headerKey(AnthropicApiProfile.HEADER_USER_AGENT)));
        assertTrue(AnthropicHeaderPolicy.API_KEY_PASSTHROUGH_ALLOWED_HEADERS.contains(
                AnthropicHeaderPolicy.headerKey(AnthropicApiProfile.HEADER_X_CLIENT_REQUEST_ID)));
        assertTrue(AnthropicHeaderPolicy.API_KEY_PASSTHROUGH_ALLOWED_HEADERS.contains(
                AnthropicHeaderPolicy.headerKey(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_LANG)));
        assertTrue(AnthropicHeaderPolicy.API_KEY_PASSTHROUGH_ALLOWED_HEADERS.contains(
                AnthropicHeaderPolicy.headerKey(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_RUNTIME_VERSION)));

        assertFalse(AnthropicHeaderPolicy.API_KEY_PASSTHROUGH_ALLOWED_HEADERS.contains(
                AnthropicHeaderPolicy.headerKey(GatewaySensitiveHeaderPolicy.HEADER_AUTHORIZATION)));
        assertFalse(AnthropicHeaderPolicy.API_KEY_PASSTHROUGH_ALLOWED_HEADERS.contains(
                AnthropicHeaderPolicy.headerKey(GatewaySensitiveHeaderPolicy.HEADER_X_API_KEY)));
        assertFalse(AnthropicHeaderPolicy.API_KEY_PASSTHROUGH_ALLOWED_HEADERS.contains(
                AnthropicHeaderPolicy.headerKey(GatewaySensitiveHeaderPolicy.HEADER_X_GOOG_API_KEY)));
        assertFalse(AnthropicHeaderPolicy.API_KEY_PASSTHROUGH_ALLOWED_HEADERS.contains(
                AnthropicHeaderPolicy.headerKey(GatewaySensitiveHeaderPolicy.HEADER_COOKIE)));
        assertFalse(AnthropicHeaderPolicy.API_KEY_PASSTHROUGH_ALLOWED_HEADERS.contains(
                AnthropicHeaderPolicy.headerKey(GatewaySensitiveHeaderPolicy.HEADER_PROXY_AUTHORIZATION)));
    }
}
