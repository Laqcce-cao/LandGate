package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GatewayWebSearchToolPolicy 测试")
class GatewayWebSearchToolPolicyTest {

    @Test
    @DisplayName("OpenAI/Responses web search aliases are centralized")
    void responsesWebSearchAliasesAreCentralized() {
        assertTrue(GatewayWebSearchToolPolicy.isWebSearchToolType("web_search"));
        assertTrue(GatewayWebSearchToolPolicy.isWebSearchToolType("google_search"));
        assertTrue(GatewayWebSearchToolPolicy.isWebSearchToolType("web_search_preview"));
        assertTrue(GatewayWebSearchToolPolicy.isWebSearchToolType("web_search_20250305"));
        assertFalse(GatewayWebSearchToolPolicy.isWebSearchToolType("function"));
    }

    @Test
    @DisplayName("Anthropic server-side web search tools use web_search prefix")
    void anthropicWebSearchToolsUsePrefix() {
        assertTrue(GatewayWebSearchToolPolicy.isAnthropicServerWebSearchToolType("web_search_20250305"));
        assertTrue(GatewayWebSearchToolPolicy.isAnthropicServerWebSearchToolType("web_search"));
        assertFalse(GatewayWebSearchToolPolicy.isAnthropicServerWebSearchToolType("google_search"));
        assertFalse(GatewayWebSearchToolPolicy.isAnthropicServerWebSearchToolType("function"));
    }
}
