package com.landgate.types.gateway;

import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GatewayClientAccessPolicy tests")
class GatewayClientAccessPolicyTest {

    @Test
    @DisplayName("claude_code_only rejects non-Anthropic client routes")
    void claudeCodeOnlyRejectsNonAnthropicRoute() {
        assertTrue(GatewayClientAccessPolicy.rejectsClaudeCodeOnlyNonAnthropicRoute(
                true, Platform.OPENAI));

        assertFalse(GatewayClientAccessPolicy.rejectsClaudeCodeOnlyNonAnthropicRoute(
                true, Platform.ANTHROPIC));
        assertFalse(GatewayClientAccessPolicy.rejectsClaudeCodeOnlyNonAnthropicRoute(
                false, Platform.OPENAI));
        assertFalse(GatewayClientAccessPolicy.rejectsClaudeCodeOnlyNonAnthropicRoute(
                null, Platform.OPENAI));
    }

    @Test
    @DisplayName("group protocol rejection message uses normalized protocol id")
    void groupProtocolMessageUsesNormalizedFormat() {
        assertEquals("This group does not allow protocol: responses",
                GatewayClientAccessPolicy.groupProtocolNotAllowedMessage(" responses "));
    }
}
