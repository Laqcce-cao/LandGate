package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenAiSessionIdPolicy tests")
class OpenAiSessionIdPolicyTest {

    @Test
    @DisplayName("raw session identifiers use Sub2API xxhash64 isolation")
    void rawSessionIdentifiersUseSub2ApiXxhash64Isolation() {
        assertEquals("cd34fcacf90356dc", OpenAiSessionIdPolicy.isolateSessionId(0L, "stable-cache-key"));
        assertEquals("0ff2843cf705e84d", OpenAiSessionIdPolicy.isolateSessionId(42L, "client-session"));
        assertEquals("d3ddb7d7b6179fe5", OpenAiSessionIdPolicy.isolateSessionId(42L, "client-conversation"));
        assertEquals("21dc22ba86089f08", OpenAiSessionIdPolicy.isolateSessionId(10L, " tenant:thread "));
        assertEquals("", OpenAiSessionIdPolicy.isolateSessionId(10L, " "));
    }

    @Test
    @DisplayName("Anthropic Messages compat sessions use Sub2API SHA-256 UUID")
    void anthropicMessagesCompatSessionsUseSub2ApiSha256Uuid() {
        assertEquals("55212671-f829-455c-862e-8b4990bea870",
                OpenAiSessionIdPolicy.compatSessionUuid(0L, "stable-cache-key"));
        assertEquals("7dd09432-c866-4149-a8b5-d31947f60ad1",
                OpenAiSessionIdPolicy.compatSessionUuid(0L, "anthropic-cache-abc"));
        assertEquals("034d2550-059c-4363-92c4-09ca6efa22d2",
                OpenAiSessionIdPolicy.compatSessionUuid(10L, "tenant:thread"));
        assertEquals("", OpenAiSessionIdPolicy.compatSessionUuid(10L, " "));
    }

    @Test
    @DisplayName("generateSessionUuid matches Sub2API SHA-256 version-4 UUID shape")
    void generateSessionUuidMatchesSub2ApiSha256Version4UuidShape() {
        assertEquals("55212671-f829-455c-862e-8b4990bea870",
                OpenAiSessionIdPolicy.generateSessionUuid("cd34fcacf90356dc"));
        assertEquals("36a9e7f1-c95b-42ff-b997-43e0c5c4ce95",
                OpenAiSessionIdPolicy.generateSessionUuid(" "));
    }

    @Test
    @DisplayName("empty generateSessionUuid seed returns a random UUID like Sub2API")
    void emptyGenerateSessionUuidSeedReturnsRandomUuidLikeSub2Api() {
        String first = OpenAiSessionIdPolicy.generateSessionUuid("");
        String second = OpenAiSessionIdPolicy.generateSessionUuid(null);

        assertTrue(first.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        assertTrue(second.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        assertNotEquals(first, second);
    }
}
