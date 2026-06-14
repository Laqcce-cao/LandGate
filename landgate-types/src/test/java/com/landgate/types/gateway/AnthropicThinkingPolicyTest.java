package com.landgate.types.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AnthropicThinkingPolicy 测试")
class AnthropicThinkingPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("thinking block 类型判断集中维护")
    void detectsThinkingBlockTypes() {
        assertTrue(AnthropicThinkingPolicy.isThinkingBlockType("thinking"));
        assertTrue(AnthropicThinkingPolicy.isThinkingBlockType("redacted_thinking"));
        assertFalse(AnthropicThinkingPolicy.isThinkingBlockType("text"));
    }

    @Test
    @DisplayName("context_management 注入条件对齐 Anthropic thinking enabled/adaptive")
    void detectsContextManagementInjectionModes() throws Exception {
        assertTrue(AnthropicThinkingPolicy.shouldInjectContextManagement(
                JSON.readTree("{\"type\":\"enabled\"}")));
        assertTrue(AnthropicThinkingPolicy.shouldInjectContextManagement(
                JSON.readTree("{\"type\":\"adaptive\"}")));
        assertFalse(AnthropicThinkingPolicy.shouldInjectContextManagement(
                JSON.readTree("{\"type\":\"disabled\"}")));
        assertFalse(AnthropicThinkingPolicy.shouldInjectContextManagement(
                JSON.readTree("{}")));
        assertFalse(AnthropicThinkingPolicy.shouldInjectContextManagement(null));
    }

    @Test
    @DisplayName("context_management clear-thinking edit 值集中维护")
    void exposesContextManagementEditValues() {
        assertEquals("clear_thinking_20251015", AnthropicThinkingPolicy.CONTEXT_MANAGEMENT_CLEAR_THINKING_EDIT);
        assertEquals("all", AnthropicThinkingPolicy.CONTEXT_MANAGEMENT_KEEP_ALL);
    }
}
