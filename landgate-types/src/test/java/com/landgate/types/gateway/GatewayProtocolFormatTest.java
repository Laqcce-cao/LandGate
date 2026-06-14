package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GatewayProtocolFormat 协议格式测试")
class GatewayProtocolFormatTest {

    @Test
    @DisplayName("三协议格式 ID 稳定")
    void protocolIdsAreStable() {
        assertEquals("messages", GatewayProtocolFormat.MESSAGES.id());
        assertEquals("responses", GatewayProtocolFormat.RESPONSES.id());
        assertEquals("chat_completions", GatewayProtocolFormat.CHAT_COMPLETIONS.id());
        assertTrue(GatewayProtocolFormat.MESSAGES.is("messages"));
    }

    @Test
    @DisplayName("协议别名规范化集中维护")
    void protocolAliasesNormalizeToStableIds() {
        assertEquals("messages", GatewayProtocolFormat.normalizeId("Anthropic-Messages"));
        assertEquals("messages", GatewayProtocolFormat.normalizeId("messages api"));
        assertEquals("chat_completions", GatewayProtocolFormat.normalizeId("openai-chat"));
        assertEquals("chat_completions", GatewayProtocolFormat.normalizeId("chat completion"));
        assertEquals("responses", GatewayProtocolFormat.normalizeId("openai-responses"));
        assertEquals("responses", GatewayProtocolFormat.normalizeId("response"));
        assertEquals("gemini", GatewayProtocolFormat.normalizeId("google-gemini"));
        assertEquals("custom_protocol", GatewayProtocolFormat.normalizeId("custom protocol"));
        assertEquals("", GatewayProtocolFormat.normalizeId(null));
    }

    @Test
    @DisplayName("协议通配符判断集中维护")
    void wildcardPolicyIsCentralized() {
        assertTrue(GatewayProtocolFormat.isWildcard("*"));
        assertFalse(GatewayProtocolFormat.isWildcard("messages"));
    }
}
