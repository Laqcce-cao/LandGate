package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAiResponsesBodyPolicy 测试")
class OpenAiResponsesBodyPolicyTest {

    @Test
    @DisplayName("Responses body 字段和值集中维护")
    void responsesBodyFactsAreCentralized() {
        assertEquals("temperature", OpenAiResponsesBodyPolicy.FIELD_TEMPERATURE);
        assertEquals("top_p", OpenAiResponsesBodyPolicy.FIELD_TOP_P);
        assertEquals("metadata", OpenAiResponsesBodyPolicy.FIELD_METADATA);
        assertEquals("parallel_tool_calls", OpenAiResponsesBodyPolicy.FIELD_PARALLEL_TOOL_CALLS);
        assertEquals("tool_choice", OpenAiResponsesBodyPolicy.FIELD_TOOL_CHOICE);
        assertEquals("reasoning", OpenAiResponsesBodyPolicy.FIELD_REASONING);
        assertEquals("effort", OpenAiResponsesBodyPolicy.FIELD_EFFORT);
        assertEquals("stream_options", OpenAiResponsesBodyPolicy.FIELD_STREAM_OPTIONS);
        assertEquals("text", OpenAiResponsesBodyPolicy.FIELD_TEXT);
        assertEquals("format", OpenAiResponsesBodyPolicy.FIELD_FORMAT);
        assertEquals("message.output_text.logprobs", OpenAiResponsesBodyPolicy.INCLUDE_MESSAGE_OUTPUT_TEXT_LOGPROBS);
        assertEquals("reasoning.encrypted_content", OpenAiResponsesBodyPolicy.INCLUDE_REASONING_ENCRYPTED_CONTENT);
        assertEquals(128, OpenAiResponsesBodyPolicy.MIN_MAX_OUTPUT_TOKENS);
        assertEquals("minimal", OpenAiResponsesBodyPolicy.REASONING_EFFORT_MINIMAL);
        assertEquals("none", OpenAiResponsesBodyPolicy.REASONING_EFFORT_NONE);
        assertEquals("strict", OpenAiResponsesBodyPolicy.FIELD_STRICT);
        assertEquals("user_location", OpenAiResponsesBodyPolicy.FIELD_USER_LOCATION);
        assertEquals("country", OpenAiResponsesBodyPolicy.FIELD_COUNTRY);
        assertEquals("region", OpenAiResponsesBodyPolicy.FIELD_REGION);
        assertEquals("city", OpenAiResponsesBodyPolicy.FIELD_CITY);
        assertEquals("timezone", OpenAiResponsesBodyPolicy.FIELD_TIMEZONE);
        assertEquals("application/pdf", OpenAiResponsesBodyPolicy.DEFAULT_DOCUMENT_MEDIA_TYPE);
        assertEquals("image/png", OpenAiResponsesBodyPolicy.DEFAULT_IMAGE_MEDIA_TYPE);
    }

    @Test
    @DisplayName("Public Responses cleanup 字段集中维护")
    void publicResponsesUnsupportedFieldsAreCentralized() {
        assertTrue(OpenAiResponsesBodyPolicy.publicResponsesUnsupportedFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_MAX_OUTPUT_TOKENS));
        assertTrue(OpenAiResponsesBodyPolicy.publicResponsesUnsupportedFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_MAX_COMPLETION_TOKENS));
        assertTrue(OpenAiResponsesBodyPolicy.publicResponsesUnsupportedFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_RETENTION));
        assertTrue(OpenAiResponsesBodyPolicy.publicResponsesUnsupportedFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_SAFETY_IDENTIFIER));
        assertFalse(OpenAiResponsesBodyPolicy.publicResponsesUnsupportedFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_KEY));
    }

    @Test
    @DisplayName("API Key Responses 兼容续接清理字段集中维护")
    void apiKeyResponsesCompatUnsupportedFieldsAreCentralized() {
        assertTrue(OpenAiResponsesBodyPolicy.openAiApiKeyResponsesCompatUnsupportedFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_MAX_OUTPUT_TOKENS));
        assertTrue(OpenAiResponsesBodyPolicy.openAiApiKeyResponsesCompatUnsupportedFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_MAX_COMPLETION_TOKENS));
        assertFalse(OpenAiResponsesBodyPolicy.openAiApiKeyResponsesCompatUnsupportedFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_PREVIOUS_RESPONSE_ID));
    }

    @Test
    @DisplayName("Chat endpoint Responses-shape cleanup 字段集中维护")
    void chatEndpointResponsesShapeUnsupportedFieldsAreCentralized() {
        assertTrue(OpenAiResponsesBodyPolicy.chatEndpointResponsesShapeUnsupportedFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_RETENTION));
        assertTrue(OpenAiResponsesBodyPolicy.chatEndpointResponsesShapeUnsupportedFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_SAFETY_IDENTIFIER));
        assertTrue(OpenAiResponsesBodyPolicy.chatEndpointResponsesShapeUnsupportedFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_METADATA));
        assertTrue(OpenAiResponsesBodyPolicy.chatEndpointResponsesShapeUnsupportedFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_STREAM_OPTIONS));
        assertFalse(OpenAiResponsesBodyPolicy.chatEndpointResponsesShapeUnsupportedFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_KEY));
    }

    @Test
    @DisplayName("service_tier 按 sub2api 兼容规则归一化")
    void normalizesServiceTier() {
        assertEquals("priority", OpenAiResponsesBodyPolicy.normalizeServiceTier(" fast "));
        assertEquals("flex", OpenAiResponsesBodyPolicy.normalizeServiceTier("flex"));
        assertEquals("auto", OpenAiResponsesBodyPolicy.normalizeServiceTier("auto"));
        assertEquals("", OpenAiResponsesBodyPolicy.normalizeServiceTier("turbo"));
    }

    @Test
    @DisplayName("reasoning.effort 按 sub2api 兼容规则归一化")
    void normalizesReasoningEffort() {
        assertEquals("none", OpenAiResponsesBodyPolicy.normalizeReasoningEffort("minimal"));
        assertEquals("high", OpenAiResponsesBodyPolicy.normalizeReasoningEffort("high"));
        assertEquals("", OpenAiResponsesBodyPolicy.normalizeReasoningEffort(null));
    }

    @Test
    @DisplayName("空 base64 data URI 被识别")
    void detectsEmptyBase64DataUri() {
        assertTrue(OpenAiResponsesBodyPolicy.isEmptyBase64DataUri("data:image/png;base64,"));
        assertTrue(OpenAiResponsesBodyPolicy.isEmptyBase64DataUri("data:image/png;base64,   "));
        assertFalse(OpenAiResponsesBodyPolicy.isEmptyBase64DataUri("data:image/png;base64,abc"));
        assertFalse(OpenAiResponsesBodyPolicy.isEmptyBase64DataUri("https://example.com/image.png"));
    }

    @Test
    @DisplayName("base64 data URI 构造集中维护")
    void buildsBase64DataUri() {
        assertEquals("data:application/pdf;base64,abc",
                OpenAiResponsesBodyPolicy.base64DataUri("application/pdf", "abc"));
    }
}
