package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAiCompatSessionPolicy 测试")
class OpenAiCompatSessionPolicyTest {

    @Test
    @DisplayName("OpenAI compat session cache facts are centralized")
    void cacheFactsAreCentralized() {
        assertEquals(30, OpenAiCompatSessionPolicy.TTL_VALUE);
        assertEquals(TimeUnit.MINUTES, OpenAiCompatSessionPolicy.TTL_UNIT);
        assertEquals("openai:compat:response-id", OpenAiCompatSessionPolicy.RESPONSE_ID_CACHE);
        assertEquals("openai:compat:turn-state", OpenAiCompatSessionPolicy.TURN_STATE_CACHE);
        assertEquals("openai:compat:continuation-disabled",
                OpenAiCompatSessionPolicy.CONTINUATION_DISABLED_CACHE);
        assertEquals("openai:compat:anthropic-digest", OpenAiCompatSessionPolicy.DIGEST_CACHE);
    }

    @Test
    @DisplayName("OpenAI compat session keys are account and API-key scoped")
    void sessionKeysAreScoped() {
        assertEquals("6\u000042\u0000tenant-thread",
                OpenAiCompatSessionPolicy.sessionKey(6L, 42L, " tenant-thread "));
        assertEquals("6\u00000\u0000tenant-thread",
                OpenAiCompatSessionPolicy.sessionKey(6L, null, "tenant-thread"));

        assertEquals("", OpenAiCompatSessionPolicy.sessionKey(null, 42L, "tenant-thread"));
        assertEquals("", OpenAiCompatSessionPolicy.sessionKey(6L, 42L, " "));
    }

    @Test
    @DisplayName("Anthropic digest namespace is account and API-key scoped")
    void digestNamespaceIsScoped() {
        assertEquals("6|42|", OpenAiCompatSessionPolicy.digestNamespace(6L, 42L));
        assertEquals("6|0|", OpenAiCompatSessionPolicy.digestNamespace(6L, null));

        assertEquals("", OpenAiCompatSessionPolicy.digestNamespace(null, 42L));
        assertEquals("", OpenAiCompatSessionPolicy.digestNamespace(0L, 42L));
    }
}
