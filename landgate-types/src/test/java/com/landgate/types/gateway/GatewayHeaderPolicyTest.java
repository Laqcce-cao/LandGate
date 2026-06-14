package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Gateway header policy tests")
class GatewayHeaderPolicyTest {

    @Test
    @DisplayName("Header key normalization is lowercase and trimmed")
    void headerKeyNormalizesLowercaseAndTrimmed() {
        assertEquals("x-custom-header", GatewayHeaderPolicy.headerKey(" X-Custom-Header "));
        assertEquals("", GatewayHeaderPolicy.headerKey(null));
    }

    @Test
    @DisplayName("Case-insensitive value lookup trims values and tolerates null")
    void valueLookupIsCaseInsensitiveAndTrimmed() {
        Map<String, String> headers = Map.of("x-CoDeX-Turn-State", " state-1 ");

        assertEquals("state-1", GatewayHeaderPolicy.value(headers, "X-Codex-Turn-State"));
        assertEquals("", GatewayHeaderPolicy.value(headers, "Missing"));
        assertEquals("", GatewayHeaderPolicy.value(null, "X-Codex-Turn-State"));
        assertEquals("", GatewayHeaderPolicy.value(headers, null));
    }

    @Test
    @DisplayName("Has value follows trimmed lookup")
    void hasValueFollowsTrimmedLookup() {
        assertTrue(GatewayHeaderPolicy.hasValue(Map.of("Anthropic-Beta", " oauth-2025-04-20 "),
                "anthropic-beta"));
        assertFalse(GatewayHeaderPolicy.hasValue(Map.of("Anthropic-Beta", " "), "anthropic-beta"));
    }
}
