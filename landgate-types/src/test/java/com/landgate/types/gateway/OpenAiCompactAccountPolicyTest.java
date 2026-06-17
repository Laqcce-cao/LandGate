package com.landgate.types.gateway;

import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAiCompactAccountPolicy tests")
class OpenAiCompactAccountPolicyTest {

    @Test
    @DisplayName("compact mode and support state match Sub2API account extra semantics")
    void compactSupportSemantics() {
        assertEquals(503, OpenAiCompactAccountPolicy.UNSUPPORTED_STATUS);
        assertEquals("compact_not_supported", OpenAiCompactAccountPolicy.UNSUPPORTED_CODE);
        assertEquals("No available OpenAI accounts support /responses/compact",
                OpenAiCompactAccountPolicy.UNSUPPORTED_MESSAGE);

        assertEquals(OpenAiCompactAccountPolicy.MODE_AUTO,
                OpenAiCompactAccountPolicy.compactMode(Platform.ANTHROPIC,
                        "{\"openai_compact_mode\":\"force_on\"}"));
        assertEquals(OpenAiCompactAccountPolicy.MODE_AUTO,
                OpenAiCompactAccountPolicy.compactMode(Platform.OPENAI,
                        "{\"openai_compact_mode\":\" invalid \"}"));
        assertEquals(OpenAiCompactAccountPolicy.MODE_FORCE_ON,
                OpenAiCompactAccountPolicy.compactMode(Platform.OPENAI,
                        "{\"openai_compact_mode\":\" FORCE_ON \"}"));
        assertEquals(OpenAiCompactAccountPolicy.MODE_FORCE_OFF,
                OpenAiCompactAccountPolicy.compactMode(Platform.OPENAI,
                        "{\"openai_compact_mode\":\"force_off\"}"));

        assertTrue(OpenAiCompactAccountPolicy.allowsCompact(Platform.OPENAI, "{}"));
        assertTrue(OpenAiCompactAccountPolicy.allowsCompact(Platform.OPENAI,
                "{\"openai_compact_supported\":true}"));
        assertFalse(OpenAiCompactAccountPolicy.allowsCompact(Platform.OPENAI,
                "{\"openai_compact_supported\":false}"));
        assertTrue(OpenAiCompactAccountPolicy.allowsCompact(Platform.OPENAI,
                "{\"openai_compact_mode\":\"force_on\",\"openai_compact_supported\":false}"));
        assertFalse(OpenAiCompactAccountPolicy.allowsCompact(Platform.OPENAI,
                "{\"openai_compact_mode\":\"force_off\",\"openai_compact_supported\":true}"));

        assertEquals(1, OpenAiCompactAccountPolicy.compactSupportTier(Platform.OPENAI, "{}"));
        assertEquals(2, OpenAiCompactAccountPolicy.compactSupportTier(Platform.OPENAI,
                "{\"openai_compact_supported\":true}"));
        assertEquals(0, OpenAiCompactAccountPolicy.compactSupportTier(Platform.OPENAI,
                "{\"openai_compact_supported\":false}"));
    }

    @Test
    @DisplayName("compact model mapping supports exact and longest wildcard matches")
    void compactModelMapping() {
        assertFalse(OpenAiCompactAccountPolicy.resolveCompactMappedModel(null, "gpt-5.4").matched());

        OpenAiCompactAccountPolicy.CompactModelMapping exact =
                OpenAiCompactAccountPolicy.resolveCompactMappedModel("""
                        {"compact_model_mapping":{"gpt-5.4":"gpt-5.4-openai-compact","invalid":1}}
                        """, "gpt-5.4");
        assertTrue(exact.matched());
        assertEquals("gpt-5.4-openai-compact", exact.model());

        OpenAiCompactAccountPolicy.CompactModelMapping wildcard =
                OpenAiCompactAccountPolicy.resolveCompactMappedModel("""
                        {"compact_model_mapping":{
                          "gpt-*":"fallback-compact",
                          "gpt-5.4*":"gpt-5.4-openai-compact",
                          "gpt-5.4-mini*":"gpt-5.4-mini-openai-compact"
                        }}
                        """, "gpt-5.4-mini");
        assertTrue(wildcard.matched());
        assertEquals("gpt-5.4-mini-openai-compact", wildcard.model());

        OpenAiCompactAccountPolicy.CompactModelMapping passthrough =
                OpenAiCompactAccountPolicy.resolveCompactMappedModel("""
                        {"compact_model_mapping":{"gpt-5.4":"gpt-5.4"}}
                        """, "gpt-5.4");
        assertTrue(passthrough.matched());
        assertEquals("gpt-5.4", passthrough.model());
    }
}
