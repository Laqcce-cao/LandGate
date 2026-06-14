package com.landgate.types.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Compat prompt_cache_key policy tests")
class CompatPromptCacheKeyPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("metadata.user_id session derives stable anthropic metadata prompt_cache_key")
    void metadataSessionDerivesPromptCacheKey() throws Exception {
        var request = JSON.readTree("""
                {
                  "model": "claude-sonnet-4",
                  "metadata": {
                    "user_id": "{\\"device_id\\":\\"device-1\\",\\"account_uuid\\":\\"account-1\\",\\"session_id\\":\\"session-1\\"}"
                  },
                  "messages": []
                }
                """);

        String key = CompatPromptCacheKeyPolicy.deriveAnthropicCompatPromptCacheKey(request);

        assertTrue(key.startsWith("anthropic-metadata-"));
        assertEquals(key, CompatPromptCacheKeyPolicy.deriveAnthropicCompatPromptCacheKey(request));
    }

    @Test
    @DisplayName("cache_control text anchors derive prompt_cache_key and session anchor")
    void cacheControlAnchorDerivesPromptCacheKey() throws Exception {
        var request = JSON.readTree("""
                {
                  "model": "claude-sonnet-4",
                  "system": [{"type":"text","text":"system anchor","cache_control":{"type":"ephemeral"}}],
                  "messages": [
                    {"role":"user","content":[{"type":"text","text":"user anchor","cache_control":{"type":"ephemeral"}}]},
                    {"role":"assistant","content":[{"type":"text","text":"assistant anchor","cache_control":{"type":"ephemeral"}}]}
                  ]
                }
                """);

        String key = CompatPromptCacheKeyPolicy.deriveAnthropicCacheControlPromptCacheKey(request);
        String anchor = CompatPromptCacheKeyPolicy.deriveAnthropicCacheControlSessionAnchor(request);

        assertTrue(key.startsWith("anthropic-cache-"));
        assertEquals("system:system anchor\nassistant:assistant anchor\nuser_anchor:user anchor", anchor);
    }

    @Test
    @DisplayName("digest fallback is only auto-injected for GPT-5/Codex compatibility models")
    void digestFallbackIsModelScoped() throws Exception {
        var codexRequest = JSON.readTree("""
                {"model":"gpt-5-codex","messages":[{"role":"user","content":"hello"}]}
                """);
        var otherRequest = JSON.readTree("""
                {"model":"gpt-4.1","messages":[{"role":"user","content":"hello"}]}
                """);

        assertTrue(CompatPromptCacheKeyPolicy.deriveAnthropicCompatPromptCacheKey(codexRequest)
                .startsWith("anthropic-digest-"));
        assertEquals("", CompatPromptCacheKeyPolicy.deriveAnthropicCompatPromptCacheKey(otherRequest));
    }

    @Test
    @DisplayName("injectPromptCacheKey preserves existing explicit key")
    void injectPromptCacheKeyPreservesExistingKey() {
        String existing = "{\"prompt_cache_key\":\"already\"}";
        String injected = CompatPromptCacheKeyPolicy.injectPromptCacheKey("{}", " derived ");

        assertEquals(existing, CompatPromptCacheKeyPolicy.injectPromptCacheKey(existing, "derived"));
        assertTrue(injected.contains("\"prompt_cache_key\":\"derived\""));
        assertEquals("derived", CompatPromptCacheKeyPolicy.extractPromptCacheKey(injected));
        assertFalse(CompatPromptCacheKeyPolicy.shouldAutoInjectPromptCacheKeyForCompat("gpt-4.1"));
    }
}
