package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GatewayAccountHealthPolicy tests")
class GatewayAccountHealthPolicyTest {

    @Test
    @DisplayName("429 uses explicit Retry-After when present")
    void tooManyRequestsUsesExplicitRetryAfter() {
        GatewayAccountHealthPolicy.Decision decision =
                GatewayAccountHealthPolicy.decideRetryableStatus(429, 0, Optional.of("42"));

        assertEquals(GatewayAccountHealthPolicy.Action.RATE_LIMITED, decision.action());
        assertEquals(42, decision.cooldownSeconds());
        assertTrue(decision.explicitRetryAfter());
    }

    @Test
    @DisplayName("429 falls back to default cooldown for missing or invalid Retry-After")
    void tooManyRequestsUsesDefaultCooldownForMissingOrInvalidRetryAfter() {
        GatewayAccountHealthPolicy.Decision missing =
                GatewayAccountHealthPolicy.decideRetryableStatus(429, 0, Optional.empty());
        GatewayAccountHealthPolicy.Decision invalid =
                GatewayAccountHealthPolicy.decideRetryableStatus(429, 0, Optional.of("not-a-number"));

        assertEquals(GatewayAccountHealthPolicy.DEFAULT_RATE_LIMIT_COOLDOWN_SECONDS,
                missing.cooldownSeconds());
        assertFalse(missing.explicitRetryAfter());
        assertEquals(GatewayAccountHealthPolicy.DEFAULT_RATE_LIMIT_COOLDOWN_SECONDS,
                invalid.cooldownSeconds());
        assertTrue(invalid.explicitRetryAfter());
    }

    @Test
    @DisplayName("529 and 503 map to fixed cooldown decisions")
    void overloadedAndServiceUnavailableUseFixedCooldowns() {
        GatewayAccountHealthPolicy.Decision overloaded =
                GatewayAccountHealthPolicy.decideRetryableStatus(529, 0, Optional.empty());
        GatewayAccountHealthPolicy.Decision unavailable =
                GatewayAccountHealthPolicy.decideRetryableStatus(503, 1, Optional.empty());

        assertEquals(GatewayAccountHealthPolicy.Action.OVERLOADED, overloaded.action());
        assertEquals(GatewayAccountHealthPolicy.OVERLOADED_COOLDOWN_SECONDS, overloaded.cooldownSeconds());
        assertEquals(GatewayAccountHealthPolicy.Action.TEMP_UNSCHEDULABLE, unavailable.action());
        assertEquals(GatewayAccountHealthPolicy.SERVICE_UNAVAILABLE_COOLDOWN_SECONDS,
                unavailable.cooldownSeconds());
        assertEquals("Upstream 503 at failover=1", unavailable.reason());
    }

    @Test
    @DisplayName("generic 5xx applies only after consecutive failover threshold")
    void generic5xxAppliesOnlyAfterThreshold() {
        GatewayAccountHealthPolicy.Decision first =
                GatewayAccountHealthPolicy.decideRetryableStatus(500, 1, Optional.empty());
        GatewayAccountHealthPolicy.Decision third =
                GatewayAccountHealthPolicy.decideRetryableStatus(500, 2, Optional.empty());

        assertFalse(first.applies());
        assertEquals(GatewayAccountHealthPolicy.Action.TEMP_UNSCHEDULABLE, third.action());
        assertEquals(GatewayAccountHealthPolicy.CONSECUTIVE_5XX_COOLDOWN_SECONDS,
                third.cooldownSeconds());
        assertEquals("Consecutive 5xx at failover=2", third.reason());
    }
}
