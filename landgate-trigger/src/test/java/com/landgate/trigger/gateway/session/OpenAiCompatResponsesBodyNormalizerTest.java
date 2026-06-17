package com.landgate.trigger.gateway.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenAI compat Responses body normalizer tests")
class OpenAiCompatResponsesBodyNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("缺失 instructions 时补空字符串")
    void ensureInstructionsField() throws Exception {
        String normalized = OpenAiCompatResponsesBodyNormalizer.ensureInstructionsField(
                "{\"model\":\"gpt-5.5\",\"input\":[]}");

        JsonNode root = JSON.readTree(normalized);
        assertTrue(root.has("instructions"));
        assertEquals("", root.get("instructions").asText());
    }

    @Test
    @DisplayName("API key Responses compat 删除不支持字段")
    void removeOpenAiApiKeyUnsupportedResponseFields() throws Exception {
        String normalized = OpenAiCompatResponsesBodyNormalizer.removeOpenAiApiKeyUnsupportedResponseFields("""
                {
                  "model":"gpt-5.5",
                  "max_output_tokens":1024,
                  "max_completion_tokens":1024,
                  "prompt_cache_key":"stable"
                }""");

        JsonNode root = JSON.readTree(normalized);
        assertFalse(root.has("max_output_tokens"));
        assertFalse(root.has("max_completion_tokens"));
        assertEquals("stable", root.get("prompt_cache_key").asText());
    }

    @Test
    @DisplayName("previous_response_id 续接时裁剪到最新用户轮")
    void attachPreviousResponseIdAndTrimToLatestUserTurn() throws Exception {
        String normalized = OpenAiCompatResponsesBodyNormalizer.attachPreviousResponseIdAndTrim("""
                {
                  "model":"gpt-5.5",
                  "input":[
                    {"type":"message","role":"user","content":[{"type":"input_text","text":"first"}]},
                    {"type":"message","role":"assistant","content":[{"type":"output_text","text":"ok"}]},
                    {"type":"message","role":"user","content":[{"type":"input_text","text":"second"}]}
                  ]
                }""", "resp_first");

        JsonNode root = JSON.readTree(normalized);
        JsonNode input = root.get("input");
        assertEquals("resp_first", root.get("previous_response_id").asText());
        assertEquals(1, input.size());
        assertEquals("second", input.get(0).get("content").get(0).get("text").asText());
    }

    @Test
    @DisplayName("latest turn 裁剪保留工具调用输出对应的 function_call")
    void trimKeepsFunctionCallContextForToolOutputs() throws Exception {
        String normalized = OpenAiCompatResponsesBodyNormalizer.attachPreviousResponseIdAndTrim("""
                {
                  "model":"gpt-5.5",
                  "input":[
                    {"type":"message","role":"user","content":[{"type":"input_text","text":"inspect"}]},
                    {"type":"function_call","call_id":"call_one","name":"Read","arguments":"{}"},
                    {"type":"function_call","call_id":"call_two","name":"Read","arguments":"{}"},
                    {"type":"function_call_output","call_id":"call_one","output":"a"},
                    {"type":"function_call_output","call_id":"call_two","output":"b"},
                    {"type":"message","role":"user","content":[{"type":"input_text","text":"continue"}]}
                  ]
                }""", "resp_tools");

        JsonNode input = JSON.readTree(normalized).get("input");
        assertEquals(5, input.size());
        assertEquals("function_call", input.get(0).get("type").asText());
        assertEquals("call_one", input.get(0).get("call_id").asText());
        assertEquals("function_call", input.get(1).get("type").asText());
        assertEquals("call_two", input.get(1).get("call_id").asText());
        assertEquals("function_call_output", input.get(2).get("type").asText());
        assertEquals("function_call_output", input.get(3).get("type").asText());
        assertEquals("message", input.get(4).get("type").asText());
    }
}
