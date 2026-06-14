package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAiHeaderPolicy 测试")
class OpenAiHeaderPolicyTest {

    @Test
    @DisplayName("Codex OAuth allowlist 包含 Codex 兼容头但不包含入站凭证头")
    void codexOAuthAllowlistDoesNotForwardInboundCredentials() {
        assertTrue(OpenAiHeaderPolicy.CODEX_OAUTH_ALLOWED_HEADERS.contains(
                OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_OPENAI_BETA)));
        assertTrue(OpenAiHeaderPolicy.CODEX_OAUTH_ALLOWED_HEADERS.contains(
                OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_ORIGINATOR)));
        assertTrue(OpenAiHeaderPolicy.CODEX_OAUTH_ALLOWED_HEADERS.contains(
                OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_X_CODEX_TURN_STATE)));

        assertFalse(OpenAiHeaderPolicy.CODEX_OAUTH_ALLOWED_HEADERS.contains(
                OpenAiCodexProfile.headerKey(GatewaySensitiveHeaderPolicy.HEADER_AUTHORIZATION)));
        assertFalse(OpenAiHeaderPolicy.CODEX_OAUTH_ALLOWED_HEADERS.contains(
                OpenAiCodexProfile.headerKey(GatewaySensitiveHeaderPolicy.HEADER_X_API_KEY)));
        assertFalse(OpenAiHeaderPolicy.CODEX_OAUTH_ALLOWED_HEADERS.contains(
                OpenAiCodexProfile.headerKey(GatewaySensitiveHeaderPolicy.HEADER_PROXY_AUTHORIZATION)));
    }

    @Test
    @DisplayName("OpenAI API Key public routes only allow public-safe headers")
    void publicOpenAiAllowlistsAreStrict() {
        assertEquals(OpenAiHeaderPolicy.API_KEY_RESPONSES_ALLOWED_HEADERS,
                OpenAiHeaderPolicy.RAW_CHAT_ALLOWED_HEADERS);
        assertTrue(OpenAiHeaderPolicy.API_KEY_RESPONSES_ALLOWED_HEADERS.contains(
                OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_ACCEPT_LANGUAGE)));
        assertTrue(OpenAiHeaderPolicy.API_KEY_RESPONSES_ALLOWED_HEADERS.contains(
                OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_USER_AGENT)));

        assertFalse(OpenAiHeaderPolicy.API_KEY_RESPONSES_ALLOWED_HEADERS.contains(
                OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_OPENAI_BETA)));
        assertFalse(OpenAiHeaderPolicy.API_KEY_RESPONSES_ALLOWED_HEADERS.contains(
                OpenAiCodexProfile.headerKey(GatewaySensitiveHeaderPolicy.HEADER_AUTHORIZATION)));
        assertFalse(OpenAiHeaderPolicy.API_KEY_RESPONSES_ALLOWED_HEADERS.contains(
                OpenAiCodexProfile.headerKey(GatewaySensitiveHeaderPolicy.HEADER_X_API_KEY)));
    }
}
