package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GatewayToolCallIdPolicy 测试")
class GatewayToolCallIdPolicyTest {

    @Test
    @DisplayName("Responses -> Anthropic response path only strips fc_ when native prefix remains")
    void responsePathStripsOnlyNativePrefixedIds() {
        assertEquals("toolu_123",
                GatewayToolCallIdPolicy.fromResponsesCallIdForAnthropicResponse("fc_toolu_123"));
        assertEquals("call_123",
                GatewayToolCallIdPolicy.fromResponsesCallIdForAnthropicResponse("fc_call_123"));
        assertEquals("fc_plain",
                GatewayToolCallIdPolicy.fromResponsesCallIdForAnthropicResponse("fc_plain"));
    }

    @Test
    @DisplayName("Responses -> Anthropic request path adds toolu_ when Anthropic needs native id")
    void requestPathAddsAnthropicToolPrefixWhenNeeded() {
        assertEquals("toolu_123",
                GatewayToolCallIdPolicy.fromResponsesCallIdForAnthropicRequest("fc_toolu_123"));
        assertEquals("call_123",
                GatewayToolCallIdPolicy.fromResponsesCallIdForAnthropicRequest("fc_call_123"));
        assertEquals("toolu_plain",
                GatewayToolCallIdPolicy.fromResponsesCallIdForAnthropicRequest("plain"));
        assertTrue(GatewayToolCallIdPolicy.fromResponsesCallIdForAnthropicRequest(null).startsWith("toolu_"));
    }

    @Test
    @DisplayName("Anthropic -> Responses preserves tool_use id verbatim")
    void anthropicToResponsesPreservesToolUseId() {
        assertEquals("toolu_123", GatewayToolCallIdPolicy.toResponsesCallIdFromAnthropic("toolu_123"));
        assertEquals("custom_id", GatewayToolCallIdPolicy.toResponsesCallIdFromAnthropic("custom_id"));
        assertEquals("", GatewayToolCallIdPolicy.toResponsesCallIdFromAnthropic(null));
    }
}
