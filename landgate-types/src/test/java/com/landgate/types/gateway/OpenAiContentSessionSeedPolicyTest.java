package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenAI content session seed policy tests")
class OpenAiContentSessionSeedPolicyTest {

    @Test
    @DisplayName("Chat seed is stable across later turns")
    void chatSeedStableAcrossLaterTurns() {
        String turn1 = """
                {
                  "model":"gpt-5.4",
                  "messages":[
                    {"role":"system","content":"You are helpful."},
                    {"role":"user","content":"Hello"}
                  ]
                }
                """;
        String turn2 = """
                {
                  "model":"gpt-5.4",
                  "messages":[
                    {"role":"system","content":"You are helpful."},
                    {"role":"user","content":"Hello"},
                    {"role":"assistant","content":"Hi"},
                    {"role":"user","content":"Next"}
                  ]
                }
                """;

        assertEquals(OpenAiContentSessionSeedPolicy.derive(turn1), OpenAiContentSessionSeedPolicy.derive(turn2));
        assertTrue(OpenAiContentSessionSeedPolicy.derive(turn1).startsWith(OpenAiContentSessionSeedPolicy.PREFIX));
    }

    @Test
    @DisplayName("Developer role is treated as system seed material")
    void developerRoleCountsAsSystem() {
        String seed = OpenAiContentSessionSeedPolicy.derive("""
                {
                  "model":"gpt-5.4",
                  "messages":[
                    {"role":"developer","content":"Project rules"},
                    {"role":"user","content":"Hello"}
                  ]
                }
                """);

        assertTrue(seed.contains("|system=\"Project rules\""));
        assertTrue(seed.contains("|first_user=\"Hello\""));
    }

    @Test
    @DisplayName("Responses input and instructions seed material are captured")
    void responsesInputSeedMaterialCaptured() {
        String seed = OpenAiContentSessionSeedPolicy.derive("""
                {
                  "model":"gpt-5.4",
                  "instructions":"Project rules",
                  "input":[
                    {"type":"message","role":"developer","content":"Dev rules"},
                    {"type":"message","role":"user","content":"Hello"}
                  ]
                }
                """);

        assertTrue(seed.contains("|instructions=Project rules"));
        assertTrue(seed.contains("|system=\"Dev rules\""));
        assertTrue(seed.contains("|first_user=\"Hello\""));
    }

    @Test
    @DisplayName("Canonical JSON makes tool formatting stable")
    void toolsCanonicalized() {
        String compact = """
                {"model":"gpt-5.4","tools":[{"type":"function","function":{"name":"get_weather","description":"Get weather"}}],"messages":[{"role":"user","content":"Hi"}]}
                """;
        String spaced = """
                {
                  "model":"gpt-5.4",
                  "tools":[{"type":"function","function":{"description":"Get weather","name":"get_weather"}}],
                  "messages":[{"role":"user","content":"Hi"}]
                }
                """;

        assertEquals(OpenAiContentSessionSeedPolicy.derive(compact), OpenAiContentSessionSeedPolicy.derive(spaced));
    }

    @Test
    @DisplayName("Empty input returns no seed")
    void emptyInputReturnsNoSeed() {
        assertEquals("", OpenAiContentSessionSeedPolicy.derive((String) null));
        assertEquals("", OpenAiContentSessionSeedPolicy.derive("{}"));
        assertFalse(OpenAiContentSessionSeedPolicy.derive("not json").startsWith(OpenAiContentSessionSeedPolicy.PREFIX));
    }
}
