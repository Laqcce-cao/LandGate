package com.landgate.trigger.gateway.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OAuthMimicryService 测试")
class OAuthMimicryServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final OAuthMimicryService service = new OAuthMimicryService();

    @Test
    @DisplayName("normalize 默认剥离 system cache_control")
    void normalizeStripsSystemCacheControlByDefault() throws Exception {
        String body = """
                {
                    "model": "claude-sonnet-4-5",
                    "system": [{"type":"text","text":"Stable","cache_control":{"type":"ephemeral"}}],
                    "messages": [{"role":"user","content":"Hello"}]
                }""";

        JsonNode normalized = JSON.readTree(service.normalizeClaudeOAuthRequestBody(body, "claude-sonnet-4-5"));

        assertFalse(normalized.get("system").get(0).has("cache_control"));
    }

    @Test
    @DisplayName("normalize 可保留 rewrite 后的 system cache_control")
    void normalizeCanPreserveSystemCacheControl() throws Exception {
        String body = """
                {
                    "model": "claude-sonnet-4-5",
                    "system": [{"type":"text","text":"Stable","cache_control":{"type":"ephemeral"}}],
                    "messages": [{"role":"user","content":"Hello"}]
                }""";

        JsonNode normalized = JSON.readTree(service.normalizeClaudeOAuthRequestBody(
                body, "claude-sonnet-4-5", false, null, false));

        assertTrue(normalized.get("system").get(0).has("cache_control"));
        assertEquals("ephemeral", normalized.get("system").get(0).get("cache_control").get("type").asText());
    }
}
