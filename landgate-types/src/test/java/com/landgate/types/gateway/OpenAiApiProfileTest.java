package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("OpenAiApiProfile 测试")
class OpenAiApiProfileTest {

    @Test
    @DisplayName("OpenAI generic API header/media/auth facts 集中维护")
    void genericApiFactsAreCentralized() {
        assertEquals("Accept", OpenAiApiProfile.HEADER_ACCEPT);
        assertEquals("Authorization", OpenAiApiProfile.HEADER_AUTHORIZATION);
        assertEquals("Content-Type", OpenAiApiProfile.HEADER_CONTENT_TYPE);
        assertEquals("User-Agent", OpenAiApiProfile.HEADER_USER_AGENT);
        assertEquals("text/event-stream", OpenAiApiProfile.ACCEPT_EVENT_STREAM);
        assertEquals("application/json", OpenAiApiProfile.ACCEPT_JSON);
        assertEquals("application/json", OpenAiApiProfile.CONTENT_TYPE_JSON);
        assertEquals("Bearer token", OpenAiApiProfile.bearerToken("token"));
        assertEquals("user-agent", OpenAiApiProfile.headerKey(" User-Agent "));
    }
}
