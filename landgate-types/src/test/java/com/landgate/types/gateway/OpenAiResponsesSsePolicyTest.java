package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAiResponsesSsePolicy 测试")
class OpenAiResponsesSsePolicyTest {

    @Test
    @DisplayName("data 行解析兼容有空格和无空格格式")
    void extractsDataPayloadLikeSub2api() {
        assertEquals("{\"type\":\"x\"}",
                OpenAiResponsesSsePolicy.extractDataPayload("data: {\"type\":\"x\"}"));
        assertEquals("{\"type\":\"x\"}",
                OpenAiResponsesSsePolicy.extractDataPayload("data:{\"type\":\"x\"}"));
        assertEquals("{\"type\":\"x\"}",
                OpenAiResponsesSsePolicy.extractDataPayload("data:\t{\"type\":\"x\"}"));
        assertEquals("", OpenAiResponsesSsePolicy.extractDataPayload("data:   "));
        assertNull(OpenAiResponsesSsePolicy.extractDataPayload("event: response.completed"));
    }

    @Test
    @DisplayName("Responses SSE 事件名集中维护")
    void eventNamesAreCentralized() {
        assertEquals("response.created", OpenAiResponsesSsePolicy.EVENT_RESPONSE_CREATED);
        assertEquals("response.output_text.delta", OpenAiResponsesSsePolicy.EVENT_OUTPUT_TEXT_DELTA);
        assertEquals("response.function_call_arguments.delta",
                OpenAiResponsesSsePolicy.EVENT_FUNCTION_CALL_ARGUMENTS_DELTA);
        assertEquals("response.reasoning_summary_text.delta",
                OpenAiResponsesSsePolicy.EVENT_REASONING_SUMMARY_TEXT_DELTA);
        assertEquals("response.reasoning_text.delta", OpenAiResponsesSsePolicy.EVENT_REASONING_TEXT_DELTA);
    }

    @Test
    @DisplayName("Responses terminal 事件集中维护")
    void terminalEventsAreCentralized() {
        assertTrue(OpenAiResponsesSsePolicy.isTerminalEvent("response.completed"));
        assertTrue(OpenAiResponsesSsePolicy.isTerminalEvent("response.done"));
        assertTrue(OpenAiResponsesSsePolicy.isTerminalEvent("response.failed"));
        assertTrue(OpenAiResponsesSsePolicy.isTerminalEvent("response.incomplete"));
        assertTrue(OpenAiResponsesSsePolicy.isTerminalEvent("response.cancelled"));
        assertTrue(OpenAiResponsesSsePolicy.isTerminalEvent("response.canceled"));
        assertFalse(OpenAiResponsesSsePolicy.isTerminalEvent("response.output_text.delta"));
    }
}
