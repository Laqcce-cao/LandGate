package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAiResponsesJsonPolicy 测试")
class OpenAiResponsesJsonPolicyTest {

    @Test
    @DisplayName("Responses JSON 字段和稳定值集中维护")
    void responseJsonFactsAreCentralized() {
        assertEquals("response", OpenAiResponsesJsonPolicy.OBJECT_RESPONSE);
        assertEquals("code", OpenAiResponsesJsonPolicy.FIELD_CODE);
        assertEquals("message", OpenAiResponsesJsonPolicy.FIELD_MESSAGE);
        assertEquals("filename", OpenAiResponsesJsonPolicy.FIELD_FILENAME);
        assertEquals("file_url", OpenAiResponsesJsonPolicy.FIELD_FILE_URL);
        assertEquals("file_data", OpenAiResponsesJsonPolicy.FIELD_FILE_DATA);
        assertEquals("file_id", OpenAiResponsesJsonPolicy.FIELD_FILE_ID);
        assertEquals("properties", OpenAiResponsesJsonPolicy.FIELD_PROPERTIES);
        assertEquals("input_audio", OpenAiResponsesJsonPolicy.TYPE_INPUT_AUDIO);
        assertEquals("input_file", OpenAiResponsesJsonPolicy.TYPE_INPUT_FILE);
        assertEquals("input_image", OpenAiResponsesJsonPolicy.TYPE_INPUT_IMAGE);
        assertEquals("object", OpenAiResponsesJsonPolicy.TYPE_OBJECT);
        assertEquals("output_text", OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT);
        assertEquals("function_call", OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL);
        assertEquals("tool_call", OpenAiResponsesJsonPolicy.TYPE_TOOL_CALL);
        assertEquals("custom_tool_call", OpenAiResponsesJsonPolicy.TYPE_CUSTOM_TOOL_CALL);
        assertEquals("custom_tool_call_output", OpenAiResponsesJsonPolicy.TYPE_CUSTOM_TOOL_CALL_OUTPUT);
        assertEquals("function_call_output", OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL_OUTPUT);
        assertEquals("item_reference", OpenAiResponsesJsonPolicy.TYPE_ITEM_REFERENCE);
        assertEquals("local_shell_call", OpenAiResponsesJsonPolicy.TYPE_LOCAL_SHELL_CALL);
        assertEquals("mcp_tool_call", OpenAiResponsesJsonPolicy.TYPE_MCP_TOOL_CALL);
        assertEquals("mcp_tool_call_output", OpenAiResponsesJsonPolicy.TYPE_MCP_TOOL_CALL_OUTPUT);
        assertEquals("tool_search_call", OpenAiResponsesJsonPolicy.TYPE_TOOL_SEARCH_CALL);
        assertEquals("tool_search_output", OpenAiResponsesJsonPolicy.TYPE_TOOL_SEARCH_OUTPUT);
        assertEquals("web_search_call", OpenAiResponsesJsonPolicy.TYPE_WEB_SEARCH_CALL);
        assertEquals("assistant", OpenAiResponsesJsonPolicy.ROLE_ASSISTANT);
        assertEquals("completed", OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
        assertEquals("max_output_tokens", OpenAiResponsesJsonPolicy.DEFAULT_INCOMPLETE_REASON);
        assertEquals("resp_", OpenAiResponsesJsonPolicy.RESPONSE_ID_PREFIX);
        assertEquals("msg_", OpenAiResponsesJsonPolicy.MESSAGE_ID_PREFIX);
        assertEquals("rsn_", OpenAiResponsesJsonPolicy.REASONING_ID_PREFIX);
        assertEquals("item_", OpenAiResponsesJsonPolicy.ITEM_ID_PREFIX);
        assertEquals(24, OpenAiResponsesJsonPolicy.RESPONSE_ID_RANDOM_LENGTH);
    }

    @Test
    @DisplayName("文本输出 part 类型判断集中维护")
    void textOutputPartPolicyIsCentralized() {
        assertTrue(OpenAiResponsesJsonPolicy.isTextOutputPart(OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT));
        assertTrue(OpenAiResponsesJsonPolicy.isTextOutputPart(OpenAiResponsesJsonPolicy.TYPE_REFUSAL));
        assertFalse(OpenAiResponsesJsonPolicy.isTextOutputPart(OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL));
    }
}
