package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Gateway cache TTL policy")
class GatewayCacheTtlPolicyTest {

    @Test
    @DisplayName("only 5m and 1h are supported override targets")
    void supportedTargets() {
        assertTrue(GatewayCacheTtlPolicy.isSupportedTarget(GatewayCacheTtlPolicy.TARGET_5M));
        assertTrue(GatewayCacheTtlPolicy.isSupportedTarget(GatewayCacheTtlPolicy.TARGET_1H));
        assertFalse(GatewayCacheTtlPolicy.isSupportedTarget("30m"));
    }

    @Test
    @DisplayName("unknown target normalizes to Sub2API default 5m")
    void normalizeTarget() {
        assertEquals(GatewayCacheTtlPolicy.TARGET_5M, GatewayCacheTtlPolicy.normalizeTarget(null));
        assertEquals(GatewayCacheTtlPolicy.TARGET_5M, GatewayCacheTtlPolicy.normalizeTarget("30m"));
        assertEquals(GatewayCacheTtlPolicy.TARGET_1H, GatewayCacheTtlPolicy.normalizeTarget("1h"));
    }
}
