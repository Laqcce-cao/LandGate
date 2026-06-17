package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("GatewayNoUsageAuditPolicy tests")
class GatewayNoUsageAuditPolicyTest {

    @Test
    @DisplayName("protocol error reason is stable")
    void protocolErrorReasonIsStable() {
        assertEquals(
                "protocol_error; endpoint=CODEX_RESPONSES; parser=OpenAiUsageParser; "
                        + "client_stream=true; upstream_stream=true; handled_as_stream=true; message=missing terminal",
                GatewayNoUsageAuditPolicy.protocolErrorReason(
                        "CODEX_RESPONSES", "OpenAiUsageParser", true, true, true, "missing terminal"));
    }

    @Test
    @DisplayName("usage not parsed reason is stable")
    void usageNotParsedReasonIsStable() {
        assertEquals(
                "usage_not_parsed; endpoint=ANTHROPIC_MESSAGES; parser=AnthropicUsageParser; "
                        + "client_stream=false; upstream_stream=true; handled_as_stream=false; content_type=application/json",
                GatewayNoUsageAuditPolicy.usageNotParsedReason(
                        "ANTHROPIC_MESSAGES", "AnthropicUsageParser", false, true, false, "application/json"));
    }
}
