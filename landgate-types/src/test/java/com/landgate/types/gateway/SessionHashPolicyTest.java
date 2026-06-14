package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SessionHashPolicy 测试")
class SessionHashPolicyTest {

    @Test
    @DisplayName("Sticky session cache facts are centralized")
    void cacheFactsAreCentralized() {
        assertEquals(30, SessionHashPolicy.TTL_VALUE);
        assertEquals(TimeUnit.MINUTES, SessionHashPolicy.TTL_UNIT);
        assertEquals("session:sticky", SessionHashPolicy.CACHE_KEY);
    }

    @Test
    @DisplayName("prompt_cache_key material is API-key scoped")
    void promptCacheKeyMaterialIsScoped() {
        assertEquals("prompt_cache_key|10|tenant:thread",
                SessionHashPolicy.promptCacheKeyMaterial(10L, " tenant:thread "));
    }

    @Test
    @DisplayName("Anthropic cache anchor material is API-key scoped")
    void anthropicCacheAnchorMaterialIsScoped() {
        assertEquals("anthropic_cache|10|repo anchor",
                SessionHashPolicy.anthropicCacheAnchorMaterial(10L, " repo anchor "));
    }

    @Test
    @DisplayName("request context material normalizes semver user-agent fragments")
    void requestContextMaterialNormalizesUserAgentSemver() {
        assertEquals("1.1.1.1|client/X.Y.Z plugin/X.Y.Z|10",
                SessionHashPolicy.requestContextMaterial(" 1.1.1.1 ",
                        "client/1.2.3 plugin/10.20.30", 10L));
        assertEquals("", SessionHashPolicy.normalizeUserAgent(null));
    }
}
