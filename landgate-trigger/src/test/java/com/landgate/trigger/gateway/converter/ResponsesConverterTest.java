package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ResponsesConverter 透传转换测试")
class ResponsesConverterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("Responses 直通请求保留状态型公共字段并移除内部扩展")
    void requestFromIRPreservesResponsesOnlyFields() throws Exception {
        JsonNode ir = JSON.readTree("""
                {
                    "model": "gpt-4o",
                    "input": "Hi",
                    "previous_response_id": "resp_prev",
                    "truncation": "auto",
                    "prompt": {"id": "pmpt_123"},
                    "background": true,
                    "conversation": {"id": "conv_123"},
                    "instructions": "Follow project policy.",
                    "max_output_tokens": 4096,
                    "context_management": [{"type": "compaction", "compact_threshold": 2000}],
                    "include": [
                      "code_interpreter_call.outputs",
                      "computer_call_output.output.image_url",
                      "file_search_call.results",
                      "message.input_image.image_url",
                      "message.output_text.logprobs",
                      "reasoning.encrypted_content",
                      "web_search_call.results",
                      "web_search_call.action.sources"
                    ],
                    "tools": [
                      {"type":"file_search","vector_store_ids":["vs_123"]},
                      {"type":"computer_use_preview","display_width":1024,"display_height":768,"environment":"browser"},
                      {"type":"mcp","server_label":"docs","server_url":"https://mcp.example.com"},
                      {"type":"image_generation"},
                      {"type":"code_interpreter","container":{"type":"auto"}},
                      {"type":"local_shell"}
                    ],
                    "_landgate_stop_sequences": ["STOP"]
                }""");

        JsonNode out = JSON.readTree(new ResponsesConverter().requestFromIR(ir));

        assertEquals("resp_prev", out.get("previous_response_id").asText());
        assertEquals("auto", out.get("truncation").asText());
        assertEquals("pmpt_123", out.get("prompt").get("id").asText());
        assertTrue(out.get("background").asBoolean());
        assertEquals("conv_123", out.get("conversation").get("id").asText());
        assertEquals("Follow project policy.", out.get("instructions").asText());
        assertEquals(4096, out.get("max_output_tokens").asInt());
        assertEquals("compaction", out.get("context_management").get(0).get("type").asText());
        assertEquals(2000, out.get("context_management").get(0).get("compact_threshold").asInt());
        assertEquals("message.output_text.logprobs", out.get("include").get(4).asText());
        assertEquals("web_search_call.results", out.get("include").get(6).asText());
        assertEquals("web_search_call.action.sources", out.get("include").get(7).asText());
        assertEquals("file_search", out.get("tools").get(0).get("type").asText());
        assertEquals("computer_use_preview", out.get("tools").get(1).get("type").asText());
        assertEquals("mcp", out.get("tools").get(2).get("type").asText());
        assertEquals("image_generation", out.get("tools").get(3).get("type").asText());
        assertEquals("code_interpreter", out.get("tools").get(4).get("type").asText());
        assertEquals("local_shell", out.get("tools").get(5).get("type").asText());
        assertTrue(!out.has("_landgate_stop_sequences"));
    }

    @Test
    @DisplayName("流式 response.done 从 response.usage 提取 usage 并扣除 cached_tokens")
    void responseDoneExtractsNestedUsage() {
        StreamTranslator translator = new ResponsesConverter().createStreamToIR("gpt-5.4");

        List<String> out = translator.feed("data: {\"type\":\"response.done\",\"response\":{\"usage\":{\"input_tokens\":100,\"output_tokens\":7,\"input_tokens_details\":{\"cached_tokens\":80}}}}");

        assertEquals(1, out.size());
        assertTrue(translator.isDone());
        assertEquals(20, translator.getInputTokens());
        assertEquals(7, translator.getOutputTokens());
    }

    @Test
    @DisplayName("流式 response.failed 支持顶层 usage")
    void responseFailedExtractsTopLevelUsage() {
        StreamTranslator translator = new ResponsesConverter().createStreamToIR("gpt-5.4");

        translator.feed("data: {\"type\":\"response.failed\",\"usage\":{\"input_tokens\":12,\"output_tokens\":0}}");

        assertTrue(translator.isDone());
        assertEquals(12, translator.getInputTokens());
        assertEquals(0, translator.getOutputTokens());
    }

    @Test
    @DisplayName("流式非法 usage token 不进入 Responses 直通统计")
    void invalidUsageTokensIgnoredInPassThroughStats() {
        StreamTranslator translator = new ResponsesConverter().createStreamToIR("gpt-5.4");

        translator.feed("data: {\"type\":\"response.done\",\"response\":{\"usage\":{\"input_tokens\":\"100\",\"output_tokens\":-7,\"input_tokens_details\":{\"cached_tokens\":{\"value\":80}}}}}");

        assertTrue(translator.isDone());
        assertEquals(0, translator.getInputTokens());
        assertEquals(0, translator.getOutputTokens());
    }

    @Test
    @DisplayName("流式非文本 type 不触发 Responses 直通终止统计")
    void nonTextStreamTypeIgnoredInPassThroughStats() {
        StreamTranslator translator = new ResponsesConverter().createStreamToIR("gpt-5.4");

        translator.feed("data: {\"type\":{\"value\":\"response.done\"},\"response\":{\"usage\":{\"input_tokens\":100,\"output_tokens\":7}}}");

        assertFalse(translator.isDone());
        assertEquals(0, translator.getInputTokens());
        assertEquals(0, translator.getOutputTokens());
    }

    @Test
    @DisplayName("Responses 直通请求保留 hosted tools 官方子字段")
    void requestFromIRPreservesHostedToolSubfields() throws Exception {
        JsonNode ir = JSON.readTree("""
                {
                    "model": "gpt-4o",
                    "input": "Search and compute",
                    "tools": [
                      {
                        "type": "file_search",
                        "vector_store_ids": ["vs_123"],
                        "max_num_results": 8,
                        "ranking_options": {
                          "ranker": "auto",
                          "score_threshold": 0.25
                        },
                        "filters": {
                          "type": "eq",
                          "key": "project",
                          "value": "landgate"
                        }
                      },
                      {
                        "type": "mcp",
                        "server_label": "docs",
                        "server_url": "https://mcp.example.com",
                        "authorization": "Bearer opaque",
                        "headers": {"X-Workspace": "landgate"},
                        "allowed_tools": ["lookup"]
                      },
                      {
                        "type": "computer_use_preview",
                        "display_width": 1280,
                        "display_height": 720,
                        "environment": "browser"
                      },
                      {
                        "type": "code_interpreter",
                        "container": {
                          "type": "auto",
                          "file_ids": ["file_123"]
                        }
                      }
                    ]
                }""");

        JsonNode out = JSON.readTree(new ResponsesConverter().requestFromIR(ir));
        JsonNode fileSearch = out.get("tools").get(0);
        JsonNode mcp = out.get("tools").get(1);
        JsonNode computer = out.get("tools").get(2);
        JsonNode codeInterpreter = out.get("tools").get(3);

        assertEquals(8, fileSearch.get("max_num_results").asInt());
        assertEquals("auto", fileSearch.get("ranking_options").get("ranker").asText());
        assertEquals(0.25, fileSearch.get("ranking_options").get("score_threshold").asDouble());
        assertEquals("project", fileSearch.get("filters").get("key").asText());
        assertEquals("Bearer opaque", mcp.get("authorization").asText());
        assertEquals("landgate", mcp.get("headers").get("X-Workspace").asText());
        assertEquals("lookup", mcp.get("allowed_tools").get(0).asText());
        assertEquals(1280, computer.get("display_width").asInt());
        assertEquals(720, computer.get("display_height").asInt());
        assertEquals("browser", computer.get("environment").asText());
        assertEquals("auto", codeInterpreter.get("container").get("type").asText());
        assertEquals("file_123", codeInterpreter.get("container").get("file_ids").get(0).asText());
    }
}
