package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Gateway stream aggregation policy tests")
class GatewayStreamAggregationPolicyTest {

    @Test
    @DisplayName("Responses clients preserve malformed upstream SSE for client compatibility")
    void responsesClientPreservesMissingTerminalSse() {
        assertEquals(GatewayStreamAggregationPolicy.MissingTerminalAction.PRESERVE_UPSTREAM_SSE,
                GatewayStreamAggregationPolicy.missingTerminalAction(GatewayProtocolFormat.RESPONSES.id()));
        assertTrue(GatewayStreamAggregationPolicy.isResponsesClient("responses"));
    }

    @Test
    @DisplayName("Translated clients receive a protocol error when terminal SSE events are missing")
    void translatedClientsProtocolErrorOnMissingTerminalSse() {
        assertEquals(GatewayStreamAggregationPolicy.MissingTerminalAction.PROTOCOL_ERROR,
                GatewayStreamAggregationPolicy.missingTerminalAction(GatewayProtocolFormat.MESSAGES.id()));
        assertEquals(GatewayStreamAggregationPolicy.MissingTerminalAction.PROTOCOL_ERROR,
                GatewayStreamAggregationPolicy.missingTerminalAction(GatewayProtocolFormat.CHAT_COMPLETIONS.id()));
        assertFalse(GatewayStreamAggregationPolicy.isResponsesClient("messages"));
    }

    @Test
    @DisplayName("Protocol error envelope facts stay stable")
    void protocolErrorFactsStayStable() {
        assertEquals(502, GatewayStreamAggregationPolicy.PROTOCOL_ERROR_STATUS);
        assertEquals("upstream_error", GatewayStreamAggregationPolicy.PROTOCOL_ERROR_TYPE);
        assertEquals("stream usage incomplete: missing terminal event",
                GatewayStreamAggregationPolicy.MISSING_TERMINAL_MESSAGE);
    }
}
