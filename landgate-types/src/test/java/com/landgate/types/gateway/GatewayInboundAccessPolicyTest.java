package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("GatewayInboundAccessPolicy tests")
class GatewayInboundAccessPolicyTest {

    @Test
    @DisplayName("inbound access failure facts are centralized")
    void inboundAccessFailureFactsAreCentralized() {
        assertEquals(401, GatewayInboundAccessPolicy.STATUS_UNAUTHORIZED);
        assertEquals(403, GatewayInboundAccessPolicy.STATUS_FORBIDDEN);
        assertEquals(402, GatewayInboundAccessPolicy.STATUS_PAYMENT_REQUIRED);
        assertEquals(429, GatewayInboundAccessPolicy.STATUS_TOO_MANY_REQUESTS);
        assertEquals("authentication_error", GatewayInboundAccessPolicy.CODE_AUTHENTICATION);
        assertEquals("permission_error", GatewayInboundAccessPolicy.CODE_PERMISSION);
        assertEquals("quota_exceeded", GatewayInboundAccessPolicy.CODE_QUOTA_EXCEEDED);
        assertEquals("insufficient_balance", GatewayInboundAccessPolicy.CODE_INSUFFICIENT_BALANCE);
    }

    @Test
    @DisplayName("disabled group message is stable")
    void disabledGroupMessageIsStable() {
        assertEquals("Group 'disabled' is disabled.",
                GatewayInboundAccessPolicy.disabledGroupMessage("disabled"));
    }
}
