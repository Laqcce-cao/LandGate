package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAI compat previous_response_id 错误匹配策略测试")
class OpenAiCompatPreviousResponsePolicyTest {

    @Test
    @DisplayName("previous_response_id 错误匹配事实集中维护")
    void previousResponseErrorFactsAreCentralized() {
        assertEquals("previous_response_not_found",
                OpenAiCompatPreviousResponsePolicy.ERROR_PREVIOUS_RESPONSE_NOT_FOUND);
        assertEquals("previous response", OpenAiCompatPreviousResponsePolicy.PHRASE_PREVIOUS_RESPONSE);
        assertEquals("not found", OpenAiCompatPreviousResponsePolicy.PHRASE_NOT_FOUND);
        assertEquals("unsupported parameter", OpenAiCompatPreviousResponsePolicy.PHRASE_UNSUPPORTED_PARAMETER);
        assertEquals("only supported on responses websocket",
                OpenAiCompatPreviousResponsePolicy.PHRASE_RESPONSES_WEBSOCKET_ONLY);
        assertEquals("not supported", OpenAiCompatPreviousResponsePolicy.PHRASE_NOT_SUPPORTED);
    }

    @Test
    @DisplayName("识别 previous_response_id 不支持错误")
    void detectsUnsupportedPreviousResponseId() {
        assertTrue(OpenAiCompatPreviousResponsePolicy.isUnsupported(
                400,
                "{\"error\":{\"message\":\"Unsupported parameter: previous_response_id\"}}"));
        assertTrue(OpenAiCompatPreviousResponsePolicy.isUnsupported(
                400,
                "{\"error\":{\"message\":\"previous_response_id is only supported on responses websocket\"}}"));
        assertTrue(OpenAiCompatPreviousResponsePolicy.isUnsupported(
                400,
                "{\"error\":{\"message\":\"previous_response_id not supported\"}}"));

        assertFalse(OpenAiCompatPreviousResponsePolicy.isUnsupported(
                404,
                "{\"error\":{\"message\":\"previous_response_id not supported\"}}"));
        assertFalse(OpenAiCompatPreviousResponsePolicy.isUnsupported(
                400,
                "{\"error\":{\"message\":\"unsupported parameter: temperature\"}}"));
    }

    @Test
    @DisplayName("识别 previous_response_id 不可用或不存在错误")
    void detectsUnavailablePreviousResponseId() {
        assertTrue(OpenAiCompatPreviousResponsePolicy.isNotFound(
                400,
                "{\"error\":{\"code\":\"previous_response_not_found\"}}"));
        assertTrue(OpenAiCompatPreviousResponsePolicy.isNotFound(
                404,
                "{\"error\":{\"message\":\"Previous response was not found\"}}"));
        assertTrue(OpenAiCompatPreviousResponsePolicy.isNotFound(
                400,
                "{\"error\":{\"message\":\"Unsupported parameter: previous_response_id\"}}"));

        assertFalse(OpenAiCompatPreviousResponsePolicy.isNotFound(
                500,
                "{\"error\":{\"code\":\"previous_response_not_found\"}}"));
        assertFalse(OpenAiCompatPreviousResponsePolicy.isNotFound(
                404,
                "{\"error\":{\"message\":\"model not found\"}}"));
    }

    @Test
    @DisplayName("错误体匹配前统一 trim/lowercase")
    void normalizesErrorBody() {
        assertEquals("previous_response_id not supported",
                OpenAiCompatPreviousResponsePolicy.normalize("  Previous_Response_ID Not Supported  "));
    }
}
