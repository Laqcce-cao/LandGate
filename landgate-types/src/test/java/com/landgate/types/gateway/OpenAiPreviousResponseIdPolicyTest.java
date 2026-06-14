package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAI previous_response_id policy tests")
class OpenAiPreviousResponseIdPolicyTest {

    @Test
    @DisplayName("Classifies previous_response_id kinds using Sub2API-compatible patterns")
    void classifiesPreviousResponseIdKinds() {
        assertEquals(OpenAiPreviousResponseIdPolicy.KIND_EMPTY,
                OpenAiPreviousResponseIdPolicy.classify("   "));
        assertEquals(OpenAiPreviousResponseIdPolicy.KIND_RESPONSE_ID,
                OpenAiPreviousResponseIdPolicy.classify("resp_abc-123_X"));
        assertEquals(OpenAiPreviousResponseIdPolicy.KIND_MESSAGE_ID,
                OpenAiPreviousResponseIdPolicy.classify("msg_abc"));
        assertEquals(OpenAiPreviousResponseIdPolicy.KIND_MESSAGE_ID,
                OpenAiPreviousResponseIdPolicy.classify("message_abc"));
        assertEquals(OpenAiPreviousResponseIdPolicy.KIND_MESSAGE_ID,
                OpenAiPreviousResponseIdPolicy.classify("item_abc"));
        assertEquals(OpenAiPreviousResponseIdPolicy.KIND_MESSAGE_ID,
                OpenAiPreviousResponseIdPolicy.classify("chatcmpl_abc"));
        assertEquals(OpenAiPreviousResponseIdPolicy.KIND_MESSAGE_ID,
                OpenAiPreviousResponseIdPolicy.classify("MSG_ABC"));
        assertEquals(OpenAiPreviousResponseIdPolicy.KIND_UNKNOWN,
                OpenAiPreviousResponseIdPolicy.classify("abc"));
    }

    @Test
    @DisplayName("HTTP Responses rejection messages match Sub2API behavior")
    void httpResponsesRejectionMessagesMatchSub2Api() {
        assertFalse(OpenAiPreviousResponseIdPolicy.shouldRejectHttpResponsesPreviousResponseId(""));
        assertTrue(OpenAiPreviousResponseIdPolicy.shouldRejectHttpResponsesPreviousResponseId("msg_abc"));
        assertTrue(OpenAiPreviousResponseIdPolicy.shouldRejectHttpResponsesPreviousResponseId("resp_abc"));

        assertEquals("previous_response_id must be a response.id (resp_*), not a message id",
                OpenAiPreviousResponseIdPolicy.httpResponsesPreviousResponseIdMessage("msg_abc"));
        assertEquals("previous_response_id is only supported on Responses WebSocket v2",
                OpenAiPreviousResponseIdPolicy.httpResponsesPreviousResponseIdMessage("resp_abc"));
        assertEquals("previous_response_id is only supported on Responses WebSocket v2",
                OpenAiPreviousResponseIdPolicy.httpResponsesPreviousResponseIdMessage("abc"));
    }
}
