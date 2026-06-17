package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GatewayResponseRoutePolicy tests")
class GatewayResponseRoutePolicyTest {

    @Test
    @DisplayName("Passthrough and translation are exact client/upstream format decisions")
    void passthroughAndTranslationUseExactFormats() {
        assertTrue(GatewayResponseRoutePolicy.isPassthrough(
                GatewayProtocolFormat.RESPONSES.id(), GatewayProtocolFormat.RESPONSES.id()));
        assertFalse(GatewayResponseRoutePolicy.needsResponseTranslation(
                GatewayProtocolFormat.RESPONSES.id(), GatewayProtocolFormat.RESPONSES.id()));

        assertFalse(GatewayResponseRoutePolicy.isPassthrough(
                GatewayProtocolFormat.MESSAGES.id(), GatewayProtocolFormat.RESPONSES.id()));
        assertTrue(GatewayResponseRoutePolicy.needsResponseTranslation(
                GatewayProtocolFormat.MESSAGES.id(), GatewayProtocolFormat.RESPONSES.id()));

        assertFalse(GatewayResponseRoutePolicy.isPassthrough("", GatewayProtocolFormat.RESPONSES.id()));
        assertFalse(GatewayResponseRoutePolicy.needsResponseTranslation(null, GatewayProtocolFormat.RESPONSES.id()));
    }

    @Test
    @DisplayName("Anthropic usage normalization applies only when Messages upstream is translated")
    void anthropicUsageNormalizationAppliesOnlyForTranslatedMessagesUpstream() {
        assertTrue(GatewayResponseRoutePolicy.shouldNormalizeAnthropicUsageForTranslation(
                GatewayProtocolFormat.MESSAGES.id(), GatewayProtocolFormat.RESPONSES.id()));

        assertFalse(GatewayResponseRoutePolicy.shouldNormalizeAnthropicUsageForTranslation(
                GatewayProtocolFormat.MESSAGES.id(), GatewayProtocolFormat.MESSAGES.id()));
        assertFalse(GatewayResponseRoutePolicy.shouldNormalizeAnthropicUsageForTranslation(
                GatewayProtocolFormat.RESPONSES.id(), GatewayProtocolFormat.MESSAGES.id()));
    }

    @Test
    @DisplayName("Responses protocol terminal is required only for translated Responses streams")
    void responsesProtocolTerminalAppliesOnlyForTranslatedResponses() {
        assertTrue(GatewayResponseRoutePolicy.usesResponsesProtocolTerminal(
                GatewayProtocolFormat.RESPONSES.id(), true));
        assertFalse(GatewayResponseRoutePolicy.usesResponsesProtocolTerminal(
                GatewayProtocolFormat.RESPONSES.id(), false));
        assertFalse(GatewayResponseRoutePolicy.usesResponsesProtocolTerminal(
                GatewayProtocolFormat.CHAT_COMPLETIONS.id(), true));
    }

    @Test
    @DisplayName("Core protocol upstream streams require terminal validation")
    void coreProtocolStreamsRequireTerminalValidation() {
        assertTrue(GatewayResponseRoutePolicy.requiresProtocolTerminal(GatewayProtocolFormat.MESSAGES.id()));
        assertTrue(GatewayResponseRoutePolicy.requiresProtocolTerminal(GatewayProtocolFormat.RESPONSES.id()));
        assertTrue(GatewayResponseRoutePolicy.requiresProtocolTerminal(GatewayProtocolFormat.CHAT_COMPLETIONS.id()));
        assertFalse(GatewayResponseRoutePolicy.requiresProtocolTerminal("gemini"));
        assertFalse(GatewayResponseRoutePolicy.requiresProtocolTerminal(null));
    }
}
