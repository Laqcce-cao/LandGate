package com.landgate.domain.billing.service;

import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.types.gateway.GatewayCacheTtlPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("UsageTokens cache TTL override")
class UsageTokensTest {

    @Test
    @DisplayName("aggregate cache creation tokens fall back to 5m before override")
    void aggregateCacheCreationFallbacksTo5m() {
        UsageTokens usage = UsageTokens.builder()
                .cacheCreationTokens(12)
                .build();

        boolean changed = usage.applyCacheTtlOverride(GatewayCacheTtlPolicy.TARGET_5M);

        assertFalse(changed);
        assertEquals(12, usage.getCacheCreation5mTokens());
        assertEquals(0, usage.getCacheCreation1hTokens());
    }

    @Test
    @DisplayName("5m and 1h cache creation tokens are moved to target 1h")
    void overrideTo1h() {
        UsageTokens usage = UsageTokens.builder()
                .cacheCreation5mTokens(7)
                .cacheCreation1hTokens(5)
                .build();

        boolean changed = usage.applyCacheTtlOverride(GatewayCacheTtlPolicy.TARGET_1H);

        assertTrue(changed);
        assertEquals(0, usage.getCacheCreation5mTokens());
        assertEquals(12, usage.getCacheCreation1hTokens());
    }

    @Test
    @DisplayName("unknown target uses Sub2API default 5m")
    void unknownTargetDefaultsTo5m() {
        UsageTokens usage = UsageTokens.builder()
                .cacheCreation5mTokens(2)
                .cacheCreation1hTokens(3)
                .build();

        boolean changed = usage.applyCacheTtlOverride("invalid");

        assertTrue(changed);
        assertEquals(5, usage.getCacheCreation5mTokens());
        assertEquals(0, usage.getCacheCreation1hTokens());
    }

    @Test
    @DisplayName("force cache billing moves input tokens to cache read tokens")
    void forceCacheBillingMovesInputToCacheRead() {
        UsageTokens usage = UsageTokens.builder()
                .inputTokens(17)
                .cacheReadTokens(3)
                .outputTokens(11)
                .build();

        boolean changed = usage.applyForceCacheBilling();

        assertTrue(changed);
        assertEquals(0, usage.getInputTokens());
        assertEquals(20, usage.getCacheReadTokens());
        assertEquals(11, usage.getOutputTokens());
    }

    @Test
    @DisplayName("force cache billing is a no-op without input tokens")
    void forceCacheBillingNoOpsWithoutInputTokens() {
        UsageTokens usage = UsageTokens.builder()
                .inputTokens(0)
                .cacheReadTokens(5)
                .build();

        boolean changed = usage.applyForceCacheBilling();

        assertFalse(changed);
        assertEquals(0, usage.getInputTokens());
        assertEquals(5, usage.getCacheReadTokens());
    }
}
