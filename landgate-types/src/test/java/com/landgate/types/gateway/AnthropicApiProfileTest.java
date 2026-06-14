package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("AnthropicApiProfile 测试")
class AnthropicApiProfileTest {

    @Test
    @DisplayName("Anthropic API header/profile 常量稳定")
    void anthropicApiConstantsAreStable() {
        assertEquals("2023-06-01", AnthropicApiProfile.ANTHROPIC_VERSION);
        assertEquals("oauth-2025-04-20", AnthropicApiProfile.BETA_OAUTH);
        assertEquals("anthropic-version", AnthropicApiProfile.HEADER_ANTHROPIC_VERSION);
        assertEquals("anthropic-beta", AnthropicApiProfile.HEADER_ANTHROPIC_BETA);
        assertEquals("x-api-key", AnthropicApiProfile.HEADER_X_API_KEY);
        assertEquals("application/json", AnthropicApiProfile.MEDIA_TYPE_JSON);
        assertEquals("Bearer token", AnthropicApiProfile.bearerToken("token"));
    }
}
