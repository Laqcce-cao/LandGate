package com.landgate.trigger.gateway.transformer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAiNormalizerProfile 测试")
class OpenAiNormalizerProfileTest {

    @Test
    @DisplayName("Codex unsupported fields 集中维护 prompt cache 相关字段")
    void codexUnsupportedFieldsIncludePromptCachePolicyFields() {
        assertTrue(OpenAiNormalizerProfile.codexUnsupportedFields()
                .contains(OpenAiNormalizerProfile.FIELD_PROMPT_CACHE_KEY));
        assertTrue(OpenAiNormalizerProfile.codexUnsupportedFields()
                .contains(OpenAiNormalizerProfile.FIELD_STREAM_OPTIONS));
        assertFalse(OpenAiNormalizerProfile.codexUnsupportedFields().contains("previous_response_id"));
    }

    @Test
    @DisplayName("Public Responses cleanup 字段集中维护")
    void publicResponsesUnsupportedFieldsAreCentralized() {
        assertTrue(OpenAiNormalizerProfile.publicResponsesUnsupportedFields()
                .contains(OpenAiNormalizerProfile.FIELD_MAX_OUTPUT_TOKENS));
        assertTrue(OpenAiNormalizerProfile.publicResponsesUnsupportedFields()
                .contains(OpenAiNormalizerProfile.FIELD_MAX_COMPLETION_TOKENS));
        assertTrue(OpenAiNormalizerProfile.publicResponsesUnsupportedFields()
                .contains(OpenAiNormalizerProfile.FIELD_PROMPT_CACHE_RETENTION));
        assertTrue(OpenAiNormalizerProfile.publicResponsesUnsupportedFields()
                .contains(OpenAiNormalizerProfile.FIELD_SAFETY_IDENTIFIER));
        assertFalse(OpenAiNormalizerProfile.publicResponsesUnsupportedFields()
                .contains(OpenAiNormalizerProfile.FIELD_PROMPT_CACHE_KEY));
    }

    @Test
    @DisplayName("Codex 模型别名按 sub2api 兼容规则归一化")
    void normalizeCodexModelAliases() {
        assertEquals(OpenAiNormalizerProfile.DEFAULT_CODEX_MODEL,
                OpenAiNormalizerProfile.normalizeCodexModel(" "));
        assertEquals("gpt-5.4-mini",
                OpenAiNormalizerProfile.normalizeCodexModel(" openai/gpt5.4mini "));
        assertEquals("gpt-5.3-codex-spark",
                OpenAiNormalizerProfile.normalizeCodexModel("gpt-5.3-codex-spark-xhigh"));
        assertEquals("gpt-5.3-codex",
                OpenAiNormalizerProfile.normalizeCodexModel("codex-mini-latest"));
        assertEquals("gemini-3-flash-preview",
                OpenAiNormalizerProfile.normalizeCodexModel("gemini-3-flash-preview"));
    }

    @Test
    @DisplayName("service_tier 与 verbosity 能力按 profile 归一化")
    void normalizeServiceTierAndTextVerbosityCapability() {
        assertEquals("priority", OpenAiNormalizerProfile.normalizeServiceTier(" fast "));
        assertEquals("flex", OpenAiNormalizerProfile.normalizeServiceTier("flex"));
        assertEquals("", OpenAiNormalizerProfile.normalizeServiceTier("turbo"));

        assertTrue(OpenAiNormalizerProfile.supportsTextVerbosity("gpt-5.3-codex"));
        assertFalse(OpenAiNormalizerProfile.supportsTextVerbosity("gpt-5.2"));
        assertTrue(OpenAiNormalizerProfile.supportsTextVerbosity("custom-model"));
    }

    @Test
    @DisplayName("空 base64 data URI 按 public Responses 清理策略识别")
    void emptyBase64DataUriDetection() {
        assertTrue(OpenAiNormalizerProfile.isEmptyBase64DataUri("data:image/png;base64,"));
        assertTrue(OpenAiNormalizerProfile.isEmptyBase64DataUri("data:image/png;base64,   "));
        assertFalse(OpenAiNormalizerProfile.isEmptyBase64DataUri("data:image/png;base64,abc"));
        assertFalse(OpenAiNormalizerProfile.isEmptyBase64DataUri("https://example.com/image.png"));
    }
}
