package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatCompletionsConverter 单元测试 —— 验证 Chat Completions API ↔ Responses IR 双向转换。
 * <p>
 * 参照：sub2api {@code chatcompletions_responses_test.go}
 */
@DisplayName("ChatCompletionsConverter 双向转换测试")
class ChatCompletionsConverterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ChatCompletionsToResponsesConverter toIR = new ChatCompletionsToResponsesConverter();
    private final ResponsesToChatCompletionsConverter fromIR = new ResponsesToChatCompletionsConverter();

    // ========================
    // Chat Completions → Responses 请求转换
    // ========================

    @Nested
    @DisplayName("Chat Completions → Responses 请求转换")
    class ChatCompletionsToResponsesRequest {

        @Test
        @DisplayName("基础文本转换")
        void basicText() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Hello"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertEquals("gpt-4o", resp.get("model").asText());
            assertFalse(resp.has("stream"));
            assertFalse(resp.get("store").asBoolean());
            assertFalse(resp.has("include"));

            JsonNode input = resp.get("input");
            assertEquals(1, input.size());
            assertEquals("user", input.get(0).get("role").asText());
        }

        @Test
        @DisplayName("显式 store 字段保留到 Responses")
        void explicitStorePreserved() throws Exception {
            String body = """
                    {
                        "model": {"id": "gpt-4o"},
                        "store": true,
                        "messages": [{"role": "user", "content": "Hello"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertTrue(resp.get("store").asBoolean());
        }

        @Test
        @DisplayName("stream 字段保留客户端语义")
        void streamFlagPreserved() throws Exception {
            String streamTrue = """
                    {
                        "model": "gpt-4o",
                        "stream": true,
                        "messages": [{"role": "user", "content": "Hello"}]
                    }""";
            String streamFalse = """
                    {
                        "model": "gpt-4o",
                        "stream": false,
                        "messages": [{"role": "user", "content": "Hello"}]
                    }""";

            assertTrue(toIR.requestToIR(streamTrue).get("stream").asBoolean());
            assertFalse(toIR.requestToIR(streamFalse).get("stream").asBoolean());
        }

        @Test
        @DisplayName("非法 max token 字段不写入 Responses IR")
        void invalidMaxTokensAreIgnored() {
            String zero = """
                    {
                        "model": "gpt-4o",
                        "max_completion_tokens": 0,
                        "messages": [{"role": "user", "content": "Hello"}]
                    }""";
            String nonNumeric = """
                    {
                        "model": "gpt-4o",
                        "max_completion_tokens": "many",
                        "messages": [{"role": "user", "content": "Hello"}]
                    }""";

            assertFalse(toIR.requestToIR(zero).has("max_output_tokens"));
            assertFalse(toIR.requestToIR(nonNumeric).has("max_output_tokens"));
        }

        @Test
        @DisplayName("developer 消息 → developer role input item")
        void developerMessage() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "developer", "content": "Project rules."},
                            {"role": "user", "content": "Hi"}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");
            assertEquals(2, input.size());
            assertEquals("developer", input.get(0).get("role").asText());
            assertEquals("Project rules.", input.get(0).get("content").asText());
        }

        @Test
        @DisplayName("system 消息 → system role input item")
        void systemMessage() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "system", "content": "You are helpful."},
                            {"role": "user", "content": "Hi"}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");
            assertEquals(2, input.size());
            assertEquals("system", input.get(0).get("role").asText());
            assertEquals("You are helpful.", input.get(0).get("content").asText());
            assertEquals("user", input.get(1).get("role").asText());
        }

        @Test
        @DisplayName("tool_calls 转换")
        void toolCalls() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "user", "content": "Call the function"},
                            {"role": "assistant", "tool_calls": [{"id":"call_1","type":"function",
                                "function":{"name":"ping","arguments":"{\\"host\\":\\"example.com\\"}"}}]},
                            {"role": "tool", "tool_call_id": "call_1", "content": "pong"}
                        ],
                        "tools": [{"type":"function","function":{"name":"ping","description":"Ping a host","parameters":{"type":"object"}}}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");

            // user + function_call + function_call_output = 3
            assertEquals(3, input.size());

            // function_call
            assertEquals("function_call", input.get(1).get("type").asText());
            assertEquals("call_1", input.get(1).get("call_id").asText());
            assertEquals("ping", input.get(1).get("name").asText());

            // function_call_output
            assertEquals("function_call_output", input.get(2).get("type").asText());
            assertEquals("call_1", input.get(2).get("call_id").asText());
            assertEquals("pong", input.get(2).get("output").asText());

            // tools
            JsonNode tools = resp.get("tools");
            assertEquals(1, tools.size());
            assertEquals("function", tools.get(0).get("type").asText());
            assertEquals("ping", tools.get(0).get("name").asText());
        }

        @Test
        @DisplayName("tool_calls 异常 arguments 形状按官方字符串边界保留")
        void toolCallAbnormalArgumentsArePreservedAsStrings() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "user", "content": "Call tools"},
                            {"role": "assistant", "tool_calls": [
                                {"type":"function","function":{"name":"missing_id","arguments":""}},
                                {"id":"call_raw","type":"function","function":{"name":"raw_args","arguments":"not-json"}}
                            ]},
                            {"role": "tool", "content": "orphan result"}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");

            assertEquals(2, input.size());
            assertEquals("function_call", input.get(1).get("type").asText());
            assertEquals("call_raw", input.get(1).get("call_id").asText());
            assertEquals("not-json", input.get(1).get("arguments").asText());
            assertFalse(input.toString().contains("missing_id"));
            assertFalse(input.toString().contains("function_call_output"));
        }

        @Test
        @DisplayName("max_tokens → max_output_tokens 精确映射")
        void maxTokens() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "max_tokens": 100,
                        "messages": [{"role": "user", "content": "Hi"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            assertEquals(100, resp.get("max_output_tokens").asInt());
        }

        @Test
        @DisplayName("max_completion_tokens 优先于 max_tokens")
        void maxCompletionTokensPreferred() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "max_tokens": 100,
                        "max_completion_tokens": 500,
                        "messages": [{"role": "user", "content": "Hi"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            assertEquals(500, resp.get("max_output_tokens").asInt());
        }

        @Test
        @DisplayName("reasoning_effort → reasoning")
        void reasoningEffort() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "reasoning_effort": "high",
                        "messages": [{"role": "user", "content": "Hi"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            assertNotNull(resp.get("reasoning"));
            assertEquals("high", resp.get("reasoning").get("effort").asText());
            assertEquals("auto", resp.get("reasoning").get("summary").asText());
        }

        @Test
        @DisplayName("response_format json_schema → text.format")
        void responseFormatJsonSchema() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Return JSON"}],
                        "response_format": {
                            "type": "json_schema",
                            "json_schema": {
                                "name": "weather_answer",
                                "description": "Weather answer",
                                "strict": true,
                                "schema": {
                                    "type": "object",
                                    "properties": {"city": {"type": "string"}},
                                    "required": ["city"],
                                    "additionalProperties": false
                                }
                            }
                        }
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode format = resp.get("text").get("format");

            assertEquals("json_schema", format.get("type").asText());
            assertEquals("weather_answer", format.get("name").asText());
            assertEquals("Weather answer", format.get("description").asText());
            assertTrue(format.get("strict").asBoolean());
            assertEquals("object", format.get("schema").get("type").asText());
        }

        @Test
        @DisplayName("response_format json_schema 缺 name/schema 不生成 Responses text.format")
        void responseFormatJsonSchemaWithoutRequiredFieldsDoesNotCreateTextFormat() throws Exception {
            String missingNameBody = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Return JSON"}],
                        "response_format": {
                            "type": "json_schema",
                            "json_schema": {
                                "schema": {"type": "object"}
                            }
                        }
                    }""";
            String missingSchemaBody = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Return JSON"}],
                        "response_format": {
                            "type": "json_schema",
                            "json_schema": {
                                "name": "weather_answer"
                            }
                        }
                    }""";

            JsonNode missingName = toIR.requestToIR(missingNameBody);
            JsonNode missingSchema = toIR.requestToIR(missingSchemaBody);

            assertFalse(missingName.has("text"));
            assertFalse(missingSchema.has("text"));
        }

        @Test
        @DisplayName("非 boolean response_format strict 不写入 Responses text.format")
        void nonBooleanResponseFormatStrictIgnoredInResponsesIR() {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Return JSON"}],
                        "response_format": {
                            "type": "json_schema",
                            "json_schema": {
                                "name": "weather_answer",
                                "description": {"text": "Weather answer"},
                                "strict": "true",
                                "schema": {"type": "object"}
                            }
                        }
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.get("text").get("format").has("description"));
            assertFalse(resp.get("text").get("format").has("strict"));
        }

        @Test
        @DisplayName("response_format json_object/text → text.format")
        void responseFormatSimpleTypes() throws Exception {
            String jsonObjectBody = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Return JSON"}],
                        "response_format": {"type": "json_object"}
                    }""";
            String textBody = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Return text"}],
                        "response_format": {"type": "text"}
                    }""";

            assertEquals("json_object",
                    toIR.requestToIR(jsonObjectBody).get("text").get("format").get("type").asText());
            assertEquals("text",
                    toIR.requestToIR(textBody).get("text").get("format").get("type").asText());
        }

        @Test
        @DisplayName("Chat 官方同义字段保留到 Responses")
        void officialSharedFieldsPreserved() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Hi"}],
                        "verbosity": "low",
                        "logprobs": true,
                        "top_logprobs": 3,
                        "safety_identifier": "user_123",
                        "prompt_cache_key": "tenant:thread",
                        "prompt_cache_retention": "24h"
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertEquals("low", resp.get("text").get("verbosity").asText());
            assertEquals(3, resp.get("top_logprobs").asInt());
            assertEquals("user_123", resp.get("safety_identifier").asText());
            assertEquals("tenant:thread", resp.get("prompt_cache_key").asText());
            assertEquals("24h", resp.get("prompt_cache_retention").asText());
            assertTrue(resp.get("include").toString().contains("message.output_text.logprobs"));
            assertFalse(resp.get("include").toString().contains("reasoning.encrypted_content"));

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(resp));
            assertEquals("tenant:thread", chat.get("prompt_cache_key").asText());
            assertEquals("24h", chat.get("prompt_cache_retention").asText());
            assertTrue(chat.get("logprobs").asBoolean());
        }

        @Test
        @DisplayName("reasoning_effort 请求 include encrypted reasoning")
        void reasoningEffortIncludesEncryptedReasoning() {
            String body = """
                    {
                        "model": "gpt-4o",
                        "reasoning_effort": "medium",
                        "messages": [{"role": "user", "content": "Think"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertEquals("medium", resp.get("reasoning").get("effort").asText());
            assertTrue(resp.get("include").toString().contains("reasoning.encrypted_content"));
        }

        @Test
        @DisplayName("非法 reasoning_effort/verbosity 不写入 Responses IR")
        void invalidReasoningEffortAndVerbosityIgnoredInResponsesIR() {
            String body = """
                    {
                        "model": "gpt-4o",
                        "reasoning_effort": "extreme",
                        "verbosity": "chatty",
                        "messages": [{"role": "user", "content": "Think"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("reasoning"));
            assertFalse(resp.has("text"));
            assertFalse(resp.has("include"));
        }

        @Test
        @DisplayName("非法 top_logprobs 不写入 Responses IR")
        void invalidTopLogprobsIgnoredInResponsesIR() {
            String nonNumeric = """
                    {
                        "model": "gpt-4o",
                        "logprobs": true,
                        "top_logprobs": "many",
                        "messages": [{"role": "user", "content": "Hello"}]
                    }""";
            String outOfRange = """
                    {
                        "model": "gpt-4o",
                        "logprobs": true,
                        "top_logprobs": 21,
                        "messages": [{"role": "user", "content": "Hello"}]
                    }""";

            assertFalse(toIR.requestToIR(nonNumeric).has("top_logprobs"));
            assertFalse(toIR.requestToIR(outOfRange).has("top_logprobs"));
            assertTrue(toIR.requestToIR(outOfRange).get("include").toString()
                    .contains("message.output_text.logprobs"));
        }

        @Test
        @DisplayName("非 boolean logprobs 不写入 Responses include")
        void nonBooleanLogprobsIgnoredInResponsesIR() {
            String body = """
                    {
                        "model": "gpt-4o",
                        "logprobs": "true",
                        "messages": [{"role": "user", "content": "Hello"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("include"));
        }

        @Test
        @DisplayName("非法 boolean/numeric 基础字段不写入 Responses IR")
        void invalidScalarFieldsIgnoredInResponsesIR() {
            String body = """
                    {
                        "model": {"id": "gpt-4o"},
                        "stream": "yes",
                        "store": "no",
                        "parallel_tool_calls": "true",
                        "temperature": "hot",
                        "top_p": {"value": 1},
                        "messages": [{"role": "user", "content": "Hello"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("model"));
            assertFalse(resp.has("stream"));
            assertFalse(resp.get("store").asBoolean());
            assertFalse(resp.has("parallel_tool_calls"));
            assertFalse(resp.has("temperature"));
            assertFalse(resp.has("top_p"));
        }

        @Test
        @DisplayName("Chat-only 参数不会虚构进 Responses IR")
        void chatOnlyParametersAreNotInventedInResponsesIR() throws Exception {
            String body = """
                    {
                        "model": {"id": "gpt-4o"},
                        "messages": [{"role": "user", "content": "Hi"}],
                        "n": 2,
                        "seed": 1234,
                        "presence_penalty": 0.5,
                        "frequency_penalty": 0.25,
                        "logit_bias": {"42": -100},
                        "modalities": ["text", "audio"],
                        "audio": {"format": "wav", "voice": "alloy"},
                        "prediction": {"type": "content", "content": "expected"}
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("n"));
            assertFalse(resp.has("seed"));
            assertFalse(resp.has("presence_penalty"));
            assertFalse(resp.has("frequency_penalty"));
            assertFalse(resp.has("logit_bias"));
            assertFalse(resp.has("modalities"));
            assertFalse(resp.has("audio"));
            assertFalse(resp.has("prediction"));
        }

        @Test
        @DisplayName("web_search_options → Responses web_search_preview tool")
        void webSearchOptionsToResponsesTool() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Search"}],
                        "web_search_options": {
                            "search_context_size": "low",
                            "user_location": {
                                "type": "approximate",
                                "approximate": {
                                    "country": "US",
                                    "region": "CA",
                                    "city": "San Francisco",
                                    "timezone": "America/Los_Angeles"
                                }
                            }
                        }
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode tool = resp.get("tools").get(0);

            assertEquals("web_search_preview", tool.get("type").asText());
            assertEquals("low", tool.get("search_context_size").asText());
            assertEquals("approximate", tool.get("user_location").get("type").asText());
            assertEquals("San Francisco", tool.get("user_location").get("city").asText());
        }

        @Test
        @DisplayName("非法 web_search_options 子字段不写入 Responses tool")
        void invalidWebSearchOptionsFieldsIgnoredInResponsesTool() {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Search"}],
                        "web_search_options": {
                            "search_context_size": "huge",
                            "user_location": {
                                "type": "approximate",
                                "approximate": {
                                    "country": 123,
                                    "city": "",
                                    "timezone": {"name": "America/Los_Angeles"}
                                }
                            }
                        }
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode tool = resp.get("tools").get(0);

            assertEquals("web_search_preview", tool.get("type").asText());
            assertFalse(tool.has("search_context_size"));
            assertFalse(tool.has("user_location"));
        }

        @Test
        @DisplayName("image_url → input_image")
        void imageURL() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": [
                            {"type":"text","text":"Describe this"},
                            {"type":"image_url","image_url":{"url":"data:image/png;base64,abc123","detail":"high"}}
                        ]}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode content = resp.get("input").get(0).get("content");

            assertEquals(2, content.size());
            assertEquals("input_text", content.get(0).get("type").asText());
            assertEquals("Describe this", content.get(0).get("text").asText());
            assertEquals("input_image", content.get(1).get("type").asText());
            assertEquals("data:image/png;base64,abc123", content.get(1).get("image_url").asText());
            assertEquals("high", content.get(1).get("detail").asText());
        }

        @Test
        @DisplayName("非法 image detail 不写入 Responses input_image")
        void invalidImageDetailIgnoredInResponsesIR() {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": [
                            {"type":"image_url","image_url":{"url":"https://example.com/a.png","detail":{"level":"high"}}}
                        ]}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode image = resp.get("input").get(0).get("content").get(0);

            assertEquals("input_image", image.get("type").asText());
            assertFalse(image.has("detail"));
        }

        @Test
        @DisplayName("Chat file/audio content parts → Responses input_file/input_audio")
        void fileAndAudioContentParts() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": [
                            {"type":"file","file":{"file_id":"file_123","filename":"brief.pdf"}},
                            {"type":"input_audio","input_audio":{"data":"UklGRg==","format":"wav"}}
                        ]}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode content = resp.get("input").get(0).get("content");

            assertEquals("input_file", content.get(0).get("type").asText());
            assertEquals("file_123", content.get(0).get("file_id").asText());
            assertEquals("brief.pdf", content.get(0).get("filename").asText());
            assertEquals("input_audio", content.get(1).get("type").asText());
            assertEquals("wav", content.get(1).get("input_audio").get("format").asText());
        }

        @Test
        @DisplayName("Chat file part 缺有效载荷时不生成 Responses input_file")
        void filePartWithoutPayloadSkipped() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": [
                            {"type":"text","text":"Read these"},
                            {"type":"file","file":{"filename":"brief.pdf"}},
                            {"type":"file","file":{"file_id":"  ","filename":"blank-id.pdf"}},
                            {"type":"file","file":{"file_data":"","filename":"blank-data.pdf"}},
                            {"type":"file","file":{"file_data":"data:application/pdf;base64,JVBERi0=","filename":"ok.pdf"}}
                        ]}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode content = resp.get("input").get(0).get("content");

            assertEquals(2, content.size());
            assertEquals("input_text", content.get(0).get("type").asText());
            assertEquals("input_file", content.get(1).get("type").asText());
            assertEquals("ok.pdf", content.get(1).get("filename").asText());
            assertEquals("data:application/pdf;base64,JVBERi0=", content.get(1).get("file_data").asText());
        }

        @Test
        @DisplayName("非文本 Chat file 字段不写入 Responses input_file")
        void nonTextChatFileFieldsIgnoredInResponsesIR() {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": [
                            {"type":"file","file":{"file_id":"file_123","filename":{"name":"brief.pdf"}}}
                        ]}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode file = resp.get("input").get(0).get("content").get(0);

            assertEquals("input_file", file.get("type").asText());
            assertEquals("file_123", file.get("file_id").asText());
            assertFalse(file.has("filename"));
        }

        @Test
        @DisplayName("空 base64 image URL 跳过")
        void emptyBase64ImageURLSkipped() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": [
                            {"type":"text","text":"Describe this"},
                            {"type":"image_url","image_url":{"url":"data:image/png;base64,"}}
                        ]}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode content = resp.get("input").get(0).get("content");
            assertEquals(1, content.size());
            assertEquals("input_text", content.get(0).get("type").asText());
        }

        @Test
        @DisplayName("空白 base64 image URL 跳过")
        void whitespaceOnlyBase64ImageURLSkipped() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": [
                            {"type":"text","text":"Describe this"},
                            {"type":"image_url","image_url":{"url":"data:image/png;base64,   "}}
                        ]}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode content = resp.get("input").get(0).get("content");
            assertEquals(1, content.size());
        }

        @Test
        @DisplayName("Chat unsupported-only user content 不生成空 Responses message")
        void unsupportedOnlyUserContentDoesNotCreateEmptyResponsesMessage() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "user", "content": [
                                {"type":"image_url","image_url":{"url":""}},
                                {"type":"input_audio","input_audio":{"data":"UklGRg=="}},
                                {"type":"file","file":{"filename":"brief.pdf"}}
                            ]},
                            {"role": "user", "content": [
                                {"type":"text","text":"visible"},
                                {"type":"image_url","image_url":{"url":"   "}},
                                {"type":"input_audio","input_audio":{"format":"wav"}}
                            ]}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");

            assertEquals(1, input.size());
            assertEquals("user", input.get(0).get("role").asText());
            JsonNode content = input.get(0).get("content");
            assertEquals(1, content.size());
            assertEquals("input_text", content.get(0).get("type").asText());
            assertEquals("visible", content.get(0).get("text").asText());
        }

        @Test
        @DisplayName("Chat 空 text part 不生成 Responses input_text")
        void emptyTextPartDoesNotCreateInputText() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "user", "content": [
                                {"type":"text","text":""},
                                {"type":"text","text":"   "}
                            ]},
                            {"role": "user", "content": [
                                {"type":"text","text":""},
                                {"type":"text","text":"visible"}
                            ]}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");

            assertEquals(1, input.size());
            JsonNode content = input.get(0).get("content");
            assertEquals(1, content.size());
            assertEquals("input_text", content.get(0).get("type").asText());
            assertEquals("visible", content.get(0).get("text").asText());
            assertFalse(input.toString().contains("\"text\":\"\""));
        }

        @Test
        @DisplayName("Chat 空 system/developer text part 不生成 Responses instruction item")
        void emptyInstructionTextPartsDoNotCreateResponsesItems() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "system", "content": [
                                {"type":"text","text":""},
                                {"type":"text","text":"   "}
                            ]},
                            {"role": "developer", "content": [
                                {"type":"text","text":""}
                            ]},
                            {"role": "user", "content": "visible"}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");

            assertEquals(1, input.size());
            assertEquals("user", input.get(0).get("role").asText());
            assertEquals("visible", input.get(0).get("content").asText());
            assertFalse(input.toString().contains("\"role\":\"system\""));
            assertFalse(input.toString().contains("\"role\":\"developer\""));
        }

        @Test
        @DisplayName("Chat 不支持的 system/developer content 不生成缺 content 的 Responses item")
        void unsupportedInstructionContentDoesNotCreateResponsesItems() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "system", "content": {"type":"unsupported"}},
                            {"role": "developer", "content": {"type":"unsupported"}},
                            {"role": "user", "content": "visible"}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");

            assertEquals(1, input.size());
            assertEquals("user", input.get(0).get("role").asText());
            assertEquals("visible", input.get(0).get("content").asText());
        }

        @Test
        @DisplayName("旧式 functions → tools + tool_choice")
        void legacyFunctions() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Hi"}],
                        "functions": [{"name":"get_weather","description":"Get weather","parameters":{"type":"object"}}],
                        "function_call": {"name":"get_weather"}
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            // tools
            JsonNode tools = resp.get("tools");
            assertEquals(1, tools.size());
            assertEquals("function", tools.get(0).get("type").asText());
            assertEquals("get_weather", tools.get(0).get("name").asText());

            // tool_choice
            JsonNode tc = resp.get("tool_choice");
            assertEquals("function", tc.get("type").asText());
            assertEquals("get_weather", tc.get("name").asText());
        }

        @Test
        @DisplayName("Chat 嵌套 tool_choice → Responses 扁平 function tool_choice")
        void nestedToolChoiceToResponsesToolChoice() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Hi"}],
                        "tools": [{"type":"function","function":{"name":"get_weather","parameters":{"type":"object"}}}],
                        "tool_choice": {"type":"function","function":{"name":"get_weather"}}
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode tc = resp.get("tool_choice");

            assertEquals("function", tc.get("type").asText());
            assertEquals("get_weather", tc.get("name").asText());
            assertFalse(tc.has("function"));
        }

        @Test
        @DisplayName("Chat custom tool → Responses custom tool")
        void customToolToResponsesTool() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "user", "content": "Use the grammar tool"},
                            {"role": "assistant", "tool_calls": [{
                                "id": "call_custom_1",
                                "type": "custom",
                                "custom": {"name": "grammar", "input": "start: expr"}
                            }]}
                        ],
                        "tools": [{
                            "type": "custom",
                            "custom": {
                                "name": "grammar",
                                "description": "Generate by grammar",
                                "format": {"type": "grammar", "grammar": {"syntax": "lark", "definition": "start: expr"}}
                            }
                        }],
                        "tool_choice": {"type": "custom", "custom": {"name": "grammar"}}
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            JsonNode tool = resp.get("tools").get(0);
            assertEquals("custom", tool.get("type").asText());
            assertEquals("grammar", tool.get("name").asText());
            assertEquals("lark", tool.get("format").get("syntax").asText());
            assertEquals("start: expr", tool.get("format").get("definition").asText());

            JsonNode customCall = resp.get("input").get(1);
            assertEquals("custom_tool_call", customCall.get("type").asText());
            assertEquals("call_custom_1", customCall.get("call_id").asText());
            assertEquals("start: expr", customCall.get("input").asText());

            JsonNode tc = resp.get("tool_choice");
            assertEquals("custom", tc.get("type").asText());
            assertEquals("grammar", tc.get("name").asText());
        }

        @Test
        @DisplayName("Chat custom tool 非官方 format 不写入 Responses")
        void invalidChatCustomToolFormatIgnored() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Use grammar"}],
                        "tools": [
                            {"type":"custom","custom":{"name":"flat","format":{"type":"grammar","syntax":"lark","definition":"start: expr"}}},
                            {"type":"custom","custom":{"name":"bad_syntax","format":{"type":"grammar","grammar":{"syntax":"peg","definition":"start: expr"}}}},
                            {"type":"custom","custom":{"name":"non_object","format":"grammar"}}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            JsonNode tools = resp.get("tools");
            assertEquals(3, tools.size());
            assertFalse(tools.get(0).has("format"));
            assertFalse(tools.get(1).has("format"));
            assertFalse(tools.get(2).has("format"));
        }

        @Test
        @DisplayName("Chat allowed_tools tool_choice → Responses 扁平 allowed_tools")
        void allowedToolsToolChoiceToResponses() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Use one tool"}],
                        "tool_choice": {
                            "type": "allowed_tools",
                            "allowed_tools": {
                                "mode": "required",
                                "tools": [
                                    {"type": "function", "function": {"name": "get_weather"}},
                                    {"type": "custom", "custom": {"name": "grammar"}}
                                ]
                            }
                        }
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode tc = resp.get("tool_choice");

            assertEquals("allowed_tools", tc.get("type").asText());
            assertEquals("required", tc.get("mode").asText());
            assertEquals("function", tc.get("tools").get(0).get("type").asText());
            assertEquals("get_weather", tc.get("tools").get(0).get("name").asText());
            assertEquals("custom", tc.get("tools").get(1).get("type").asText());
            assertEquals("grammar", tc.get("tools").get(1).get("name").asText());
        }

        @Test
        @DisplayName("Chat allowed_tools 中 unsupported tool 不透传到 Responses")
        void allowedToolsDropsUnsupportedToolsToResponses() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Use one tool"}],
                        "tool_choice": {
                            "type": "allowed_tools",
                            "allowed_tools": {
                                "mode": "required",
                                "tools": [
                                    {"type": "function", "function": {"name": "get_weather"}},
                                    {"type": "file_search", "name": "knowledge"}
                                ]
                            }
                        }
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode tools = resp.get("tool_choice").get("tools");

            assertEquals(1, tools.size());
            assertEquals("function", tools.get(0).get("type").asText());
            assertEquals("get_weather", tools.get(0).get("name").asText());
        }

        @Test
        @DisplayName("Chat allowed_tools 非法 mode 不透传到 Responses")
        void invalidAllowedToolsModeDroppedToResponses() {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Use one tool"}],
                        "tool_choice": {
                            "type": "allowed_tools",
                            "allowed_tools": {
                                "mode": {"value": "required"},
                                "tools": [
                                    {"type": "function", "function": {"name": "get_weather"}}
                                ]
                            }
                        }
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode tc = resp.get("tool_choice");

            assertEquals("allowed_tools", tc.get("type").asText());
            assertFalse(tc.has("mode"));
            assertEquals("get_weather", tc.get("tools").get(0).get("name").asText());
        }

        @Test
        @DisplayName("缺 name 的 Chat tools 不写入 Responses")
        void unnamedToolsDroppedToResponses() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Use tools"}],
                        "tools": [
                            {"type":"function","function":{"parameters":{"type":"object"}}},
                            {"type":"custom","custom":{"description":"No name"}}
                        ],
                        "functions": [
                            {"description":"Legacy without name"}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("tools"));
        }

        @Test
        @DisplayName("Chat function parameters 非对象 → Responses 空 object schema")
        void nonObjectToolParametersUseEmptySchemaToResponses() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Use tools"}],
                        "tools": [
                            {"type":"function","function":{"name":"modern","parameters":"not-a-schema"}}
                        ],
                        "functions": [
                            {"name":"legacy","parameters":["not-a-schema"]}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode tools = resp.get("tools");

            assertEquals(2, tools.size());
            for (JsonNode tool : tools) {
                JsonNode parameters = tool.get("parameters");
                assertEquals("object", parameters.get("type").asText());
                assertTrue(parameters.get("properties").isObject());
            }
        }

        @Test
        @DisplayName("unsupported Chat tool_choice 不透传到 Responses")
        void unsupportedToolChoiceDroppedToResponses() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Use one tool"}],
                        "tool_choice": {"type": "file_search", "name": "knowledge"}
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("tool_choice"));
        }

        @Test
        @DisplayName("未知文本 Chat tool_choice 不透传到 Responses")
        void unknownTextToolChoiceDroppedToResponses() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Use one tool"}],
                        "tool_choice": "file_search"
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("tool_choice"));
        }

        @Test
        @DisplayName("缺 name 的 Chat tool_choice 不透传到 Responses")
        void unnamedToolChoiceDroppedToResponses() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Use one tool"}],
                        "tool_choice": {"type": "function", "function": {}}
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("tool_choice"));
        }

        @Test
        @DisplayName("异常 legacy function_call 不透传到 Responses tool_choice")
        void malformedLegacyFunctionCallDroppedToResponses() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Use one tool"}],
                        "function_call": {"arguments": "{}"}
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("tool_choice"));
        }

        @Test
        @DisplayName("空 name 的 legacy function_call 不透传到 Responses tool_choice")
        void blankLegacyFunctionCallNameDroppedToResponses() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Use one tool"}],
                        "function_call": {"name": " "}
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("tool_choice"));
        }

        @Test
        @DisplayName("legacy function_call 不接受 required 模式")
        void legacyFunctionCallRequiredModeDroppedToResponses() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Use one tool"}],
                        "function_call": "required"
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("tool_choice"));
        }

        @Test
        @DisplayName("service_tier 透传")
        void serviceTier() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "service_tier": "flex",
                        "messages": [{"role": "user", "content": "Hi"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            assertEquals("flex", resp.get("service_tier").asText());
        }

        @Test
        @DisplayName("metadata/user/parallel_tool_calls 与 tool strict 透传")
        void commonFieldsAndToolStrictPreserved() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "service_tier": "flex",
                        "metadata": {"trace_id": "req_123"},
                        "user": "user_abc",
                        "parallel_tool_calls": false,
                        "messages": [{"role": "user", "content": "Hi"}],
                        "tools": [{"type":"function","function":{
                            "name":"get_weather",
                            "description":"Get weather",
                            "parameters":{"type":"object"},
                            "strict": true
                        }}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertEquals("req_123", resp.get("metadata").get("trace_id").asText());
            assertEquals("user_abc", resp.get("user").asText());
            assertFalse(resp.get("parallel_tool_calls").asBoolean());
            assertTrue(resp.get("tools").get(0).get("strict").asBoolean());

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(resp));
            assertEquals("flex", chat.get("service_tier").asText());
            assertEquals("req_123", chat.get("metadata").get("trace_id").asText());
            assertEquals("user_abc", chat.get("user").asText());
            assertFalse(chat.get("parallel_tool_calls").asBoolean());
            assertTrue(chat.get("tools").get(0).get("function").get("strict").asBoolean());
        }

        @Test
        @DisplayName("非 boolean tool strict 不按 true 解析")
        void nonBooleanToolStrictIsNotCoerced() {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": "Hi"}],
                        "tools": [{"type":"function","function":{
                            "name":"get_weather",
                            "description":{"text":"Get weather"},
                            "parameters":{"type":"object"},
                            "strict": "true"
                        }}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.get("tools").get(0).has("description"));
            assertFalse(resp.get("tools").get(0).get("strict").asBoolean());
        }

        @Test
        @DisplayName("非对象 metadata 不透传")
        void nonObjectMetadataDropped() throws Exception {
            String chatBody = """
                    {
                        "model": "gpt-4o",
                        "metadata": "trace_id=req_123",
                        "messages": [{"role": "user", "content": "Hi"}]
                    }""";
            String responsesBody = """
                    {
                        "model": "gpt-4o",
                        "metadata": ["trace_id"],
                        "input": [{"role": "user", "content": "Hi"}]
                    }""";

            JsonNode resp = toIR.requestToIR(chatBody);
            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(responsesBody)));

            assertFalse(resp.has("metadata"));
            assertFalse(chat.has("metadata"));
        }

        @Test
        @DisplayName("非文本公共字符串字段不透传")
        void nonTextCommonStringFieldsDropped() throws Exception {
            String chatBody = """
                    {
                        "model": "gpt-4o",
                        "instructions": {"text":"not valid"},
                        "service_tier": {"tier":"flex"},
                        "user": ["user_abc"],
                        "safety_identifier": {"id":"user_abc"},
                        "prompt_cache_key": ["tenant:thread"],
                        "prompt_cache_retention": {"ttl":"24h"},
                        "messages": [{"role": "user", "content": "Hi"}]
                    }""";
            String responsesBody = """
                    {
                        "model": "gpt-4o",
                        "service_tier": {"tier":"flex"},
                        "user": ["user_abc"],
                        "safety_identifier": {"id":"user_abc"},
                        "prompt_cache_key": ["tenant:thread"],
                        "prompt_cache_retention": {"ttl":"24h"},
                        "input": [{"role": "user", "content": "Hi"}]
                    }""";

            JsonNode resp = toIR.requestToIR(chatBody);
            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(responsesBody)));

            assertFalse(resp.has("instructions"));
            assertFalse(resp.has("service_tier"));
            assertFalse(resp.has("user"));
            assertFalse(resp.has("safety_identifier"));
            assertFalse(resp.has("prompt_cache_key"));
            assertFalse(resp.has("prompt_cache_retention"));
            assertFalse(chat.has("service_tier"));
            assertFalse(chat.has("user"));
            assertFalse(chat.has("safety_identifier"));
            assertFalse(chat.has("prompt_cache_key"));
            assertFalse(chat.has("prompt_cache_retention"));
        }

        @Test
        @DisplayName("stop → 内部 stop_sequences 扩展")
        void stopToInternalStopSequences() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "stop": ["</end>", "", 123, {"bad": true}, "DONE"],
                        "messages": [{"role": "user", "content": "Hi"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertEquals("</end>", resp.get("_landgate_stop_sequences").get(0).asText());
            assertEquals("DONE", resp.get("_landgate_stop_sequences").get(1).asText());
            assertEquals(2, resp.get("_landgate_stop_sequences").size());

            String chatBody = fromIR.requestFromIR(resp);
            JsonNode chat = JSON.readTree(chatBody);
            assertEquals("</end>", chat.get("stop").get(0).asText());
            assertEquals("DONE", chat.get("stop").get(1).asText());
            assertEquals(2, chat.get("stop").size());

            String responsesBody = new ResponsesConverter().requestFromIR(resp);
            assertFalse(JSON.readTree(responsesBody).has("_landgate_stop_sequences"));
        }

        @Test
        @DisplayName("非法 stop 不生成内部 stop 扩展")
        void invalidStopDoesNotCreateInternalExtension() {
            String body = """
                    {
                        "model": "gpt-4o",
                        "stop": ["", 123, {"bad": true}],
                        "messages": [{"role": "user", "content": "Hi"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("_landgate_stop_sequences"));
        }

        @Test
        @DisplayName("assistant 消息同时有文本和 tool_calls")
        void assistantWithTextAndToolCalls() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "user", "content": "Do something"},
                            {"role": "assistant", "content": "Let me call a function.",
                                "tool_calls": [{"id":"call_abc","type":"function","function":{"name":"do_thing","arguments":"{}"}}]}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");

            // user + assistant message + function_call = 3
            assertEquals(3, input.size());
            assertEquals("user", input.get(0).get("role").asText());
            assertEquals("assistant", input.get(1).get("role").asText());
            assertEquals("function_call", input.get(2).get("type").asText());
        }

        @Test
        @DisplayName("旧式 assistant function_call 请求 → function_call input item")
        void assistantLegacyFunctionCallRequest() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role":"user","content":"Call weather"},
                            {"role":"assistant","content":null,
                                "function_call":{"name":"get_weather","arguments":"{\\"city\\":\\"NYC\\"}"}}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");

            assertEquals(2, input.size());
            assertEquals("function_call", input.get(1).get("type").asText());
            assertEquals("get_weather", input.get(1).get("name").asText());
            assertEquals("{\"city\":\"NYC\"}", input.get(1).get("arguments").asText());
        }

        @Test
        @DisplayName("assistant 数组 content 中的 thinking 扩展 → 独立 reasoning input item")
        void assistantThinkingPartToReasoningInputItem() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "user", "content": "Hi"},
                            {"role": "assistant", "content": [
                                {"type":"thinking","thinking":"internal plan"},
                                {"type":"text","text":"final answer"}
                            ]}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");
            assertEquals(3, input.size());

            JsonNode content = input.get(1).get("content");
            assertEquals(1, content.size());
            assertEquals("output_text", content.get(0).get("type").asText());
            String text = content.get(0).get("text").asText();
            assertEquals("final answer", text);

            JsonNode reasoning = input.get(2);
            assertEquals("reasoning", reasoning.get("type").asText());
            assertEquals("internal plan", reasoning.get("content").get(0).get("text").asText());
            assertEquals("internal plan", reasoning.get("summary").get(0).get("text").asText());
        }

        @Test
        @DisplayName("assistant reasoning_content → 独立 reasoning input item")
        void assistantReasoningContentToReasoningInputItem() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "user", "content": "What is 6 * 7?"},
                            {"role": "assistant", "content": "42", "reasoning_content": "I multiplied 6 by 7."}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");

            assertEquals(3, input.size());
            assertEquals("assistant", input.get(1).get("role").asText());
            assertEquals("42", input.get(1).get("content").get(0).get("text").asText());
            assertEquals("reasoning", input.get(2).get("type").asText());
            assertEquals("I multiplied 6 by 7.", input.get(2).get("content").get(0).get("text").asText());
            assertEquals("I multiplied 6 by 7.", input.get(2).get("summary").get(0).get("text").asText());
        }

        @Test
        @DisplayName("仅 assistant reasoning_content 不生成空 message")
        void assistantReasoningContentWithoutTextDoesNotCreateEmptyMessage() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "user", "content": "Continue"},
                            {"role": "assistant", "content": null, "reasoning_content": "Prior reasoning."}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");

            assertEquals(2, input.size());
            assertEquals("user", input.get(0).get("role").asText());
            assertEquals("reasoning", input.get(1).get("type").asText());
            assertEquals("Prior reasoning.", input.get(1).get("content").get(0).get("text").asText());
        }

        @Test
        @DisplayName("tool message 数组 content → 拍平文本")
        void toolArrayContent() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "user", "content": "Use the tool"},
                            {"role": "assistant", "tool_calls": [{"id":"call_1","type":"function",
                                "function":{"name":"inspect_image","arguments":"{}"}}]},
                            {"role": "tool", "tool_call_id": "call_1",
                                "content": [{"type":"text","text":"image width: 100"},{"type":"image_url","image_url":{"url":"data:image/png;base64,ignored"}},{"type":"text","text":"; image height: 200"}]}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");
            assertEquals(3, input.size());
            assertEquals("function_call_output", input.get(2).get("type").asText());
            assertEquals("image width: 100\n; image height: 200", input.get(2).get("output").asText());
        }

        @Test
        @DisplayName("空 tool message content → 空 function_call_output.output")
        void emptyToolMessageContentStaysEmpty() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "user", "content": "Use the tool"},
                            {"role": "assistant", "tool_calls": [{"id":"call_1","type":"function",
                                "function":{"name":"inspect","arguments":"{}"}}]},
                            {"role": "tool", "tool_call_id": "call_1", "content": ""}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");

            assertEquals("function_call_output", input.get(2).get("type").asText());
            assertEquals("", input.get(2).get("output").asText());
        }

        @Test
        @DisplayName("旧式 function 消息（role=function）→ function_call_output")
        void legacyFunctionMessage() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "user", "content": "Call function"},
                            {"role": "function", "name": "calc", "content": "42"}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");
            assertEquals(2, input.size());
            assertEquals("function_call_output", input.get(1).get("type").asText());
            assertEquals("calc", input.get(1).get("call_id").asText());
            assertEquals("42", input.get(1).get("output").asText());
        }

        @Test
        @DisplayName("Chat 请求缺 id/name 的工具结构不进入 Responses IR")
        void invalidToolShapesInRequestIgnored() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "user", "content": "Use tools"},
                            {"role": "assistant", "tool_calls": [
                                {"type":"function","function":{"name":"missing_id","arguments":"{}"}},
                                {"id":"call_missing_name","type":"function","function":{"arguments":"{}"}},
                                {"id":"call_custom_missing_name","type":"custom","custom":{"input":"raw"}},
                                {"id":true,"type":"function","function":{"name":"boolean_id","arguments":"{}"}},
                                {"id":"call_boolean_name","type":"function","function":{"name":false,"arguments":"{}"}}
                            ]},
                            {"role": "tool", "tool_call_id": 123, "content": "numeric id"},
                            {"role": "tool", "content": "orphan"},
                            {"role": "function", "name": true, "content": "legacy bool"},
                            {"role": "function", "content": "legacy orphan"}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");

            assertEquals(1, input.size());
            assertEquals("user", input.get(0).get("role").asText());
            assertFalse(input.toString().contains("function_call"));
            assertFalse(input.toString().contains("function_call_output"));
            assertFalse(input.toString().contains("custom_tool_call"));
        }

        @Test
        @DisplayName("Chat 请求非文本可见内容和工具 payload 不进入 Responses IR")
        void nonTextVisibleContentAndToolPayloadIgnoredInResponsesIR() {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [
                            {"role": "system", "content": [{"type":"text","text":123}]},
                            {"role": "user", "content": [
                                {"type":"text","text":{"value":"hidden"}},
                                {"type":"image_url","image_url":{"url":456}},
                                {"type":"text","text":"visible"}
                            ]},
                            {"role": "assistant", "content": [
                                {"type":"thinking","thinking":true},
                                {"type":"refusal","refusal":789},
                                {"type":"text","text":{"value":"hidden"}}
                            ], "reasoning_content": false,
                            "tool_calls": [
                                {"id":"call_func","type":"function","function":{"name":"fn","arguments":{"x":1}}},
                                {"id":"call_custom","type":"custom","custom":{"name":"grammar","input":["raw"]}}
                            ]},
                            {"role":"tool","tool_call_id":"call_func","content":{"value":"object output"}},
                            {"role":"function","name":"legacy_fn","content":false}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");

            assertEquals(5, input.size());
            assertEquals("visible", input.get(0).get("content").get(0).get("text").asText());
            assertEquals("function_call", input.get(1).get("type").asText());
            assertEquals("{}", input.get(1).get("arguments").asText());
            assertEquals("custom_tool_call", input.get(2).get("type").asText());
            assertEquals("", input.get(2).get("input").asText());
            assertEquals("", input.get(3).get("output").asText());
            assertEquals("", input.get(4).get("output").asText());
            assertFalse(input.toString().contains("hidden"));
            assertFalse(input.toString().contains("789"));
        }
    }

    // ========================
    // Responses → Chat Completions 响应转换
    // ========================

    @Nested
    @DisplayName("Responses → Chat Completions 响应转换")
    class ResponsesToChatCompletionsResponse {

        @Test
        @DisplayName("基础文本响应")
        void basicText() throws Exception {
            String body = """
                    {
                        "id": "resp_123",
                        "created_at": 1710000000,
                        "status": "completed",
                        "output": [{"type":"message","content":[{"type":"output_text","text":"Hello, world!"}]}],
                        "usage": {"input_tokens":10,"output_tokens":5,"total_tokens":15}
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode chat = JSON.readTree(result);

            assertEquals("chat.completion", chat.get("object").asText());
            assertEquals(1710000000L, chat.get("created").asLong());
            assertEquals("unknown", chat.get("model").asText());

            JsonNode choices = chat.get("choices");
            assertEquals(1, choices.size());
            assertEquals("stop", choices.get(0).get("finish_reason").asText());
            assertEquals("Hello, world!", choices.get(0).get("message").get("content").asText());

            JsonNode usage = chat.get("usage");
            assertEquals(10, usage.get("prompt_tokens").asInt());
            assertEquals(5, usage.get("completion_tokens").asInt());
            assertEquals(15, usage.get("total_tokens").asInt());
        }

        @Test
        @DisplayName("非法 Responses usage token 不降级到 Chat usage")
        void invalidResponsesUsageTokensIgnoredInChatUsage() throws Exception {
            String body = """
                    {
                        "id": "resp_bad_usage",
                        "status": "completed",
                        "output": [{"type":"message","content":[{"type":"output_text","text":"Hello"}]}],
                        "usage": {
                            "input_tokens": "10",
                            "output_tokens": -5,
                            "input_tokens_details": {"cached_tokens": "8"},
                            "output_tokens_details": {"reasoning_tokens": {"value": 1}}
                        }
                    }""";

            JsonNode chat = JSON.readTree(fromIR.responseFromIR(JSON.readTree(body)));
            JsonNode usage = chat.get("usage");

            assertEquals(0, usage.get("prompt_tokens").asInt());
            assertEquals(0, usage.get("completion_tokens").asInt());
            assertEquals(0, usage.get("total_tokens").asInt());
            assertFalse(usage.has("prompt_tokens_details"));
            assertFalse(usage.has("completion_tokens_details"));
        }

        @Test
        @DisplayName("refusal content 作为兼容文本保留")
        void refusalContentAsText() throws Exception {
            String body = """
                    {
                        "id": "resp_refusal",
                        "status": "completed",
                        "output": [{"type":"message","content":[{"type":"refusal","refusal":"I cannot help with that."}]}]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode chat = JSON.readTree(result);

            assertEquals("I cannot help with that.",
                    chat.get("choices").get(0).get("message").get("content").asText());
        }

        @Test
        @DisplayName("tool_calls 响应")
        void toolCalls() throws Exception {
            String body = """
                    {
                        "id": "resp_456",
                        "status": "completed",
                        "output": [{"type":"function_call","call_id":"call_xyz","name":"get_weather",
                            "arguments":"{\\"city\\":\\"NYC\\"}"}]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode chat = JSON.readTree(result);

            JsonNode choices = chat.get("choices");
            assertEquals(1, choices.size());
            assertEquals("tool_calls", choices.get(0).get("finish_reason").asText());

            JsonNode message = choices.get(0).get("message");
            JsonNode toolCalls = message.get("tool_calls");
            assertEquals(1, toolCalls.size());
            assertEquals("call_xyz", toolCalls.get(0).get("id").asText());
            assertEquals("function", toolCalls.get(0).get("type").asText());
            assertEquals("get_weather", toolCalls.get(0).get("function").get("name").asText());
        }

        @Test
        @DisplayName("custom_tool_call 响应 → Chat custom tool_call")
        void customToolCallResponse() throws Exception {
            String body = """
                    {
                        "id": "resp_custom",
                        "status": "completed",
                        "output": [{"type":"custom_tool_call","call_id":"call_custom_1","name":"grammar",
                            "input":"start: expr"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.responseFromIR(JSON.readTree(body)));

            JsonNode toolCall = chat.get("choices").get(0).get("message").get("tool_calls").get(0);
            assertEquals("custom", toolCall.get("type").asText());
            assertEquals("call_custom_1", toolCall.get("id").asText());
            assertEquals("grammar", toolCall.get("custom").get("name").asText());
            assertEquals("start: expr", toolCall.get("custom").get("input").asText());
            assertEquals("tool_calls", chat.get("choices").get(0).get("finish_reason").asText());
        }

        @Test
        @DisplayName("Responses output tool call 缺 call_id/name 时不构造非法 Chat tool_calls")
        void invalidOutputToolCallsDropped() throws Exception {
            String body = """
                    {
                        "id": "resp_invalid_tools",
                        "status": "completed",
                        "output": [
                            {"type":"function_call","name":"missing_call_id","arguments":"{}"},
                            {"type":"custom_tool_call","call_id":"call_missing_name","input":"raw"},
                            {"type":"function_call","call_id":true,"name":"boolean_call_id","arguments":"{}"},
                            {"type":"function_call","call_id":"call_boolean_name","name":false,"arguments":"{}"},
                            {"type":"function_call","call_id":"call_ok","name":"ok","arguments":"{\\"x\\":1}"}
                        ]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.responseFromIR(JSON.readTree(body)));
            JsonNode toolCalls = chat.get("choices").get(0).get("message").get("tool_calls");

            assertEquals(1, toolCalls.size());
            assertEquals("call_ok", toolCalls.get(0).get("id").asText());
            assertEquals("ok", toolCalls.get(0).get("function").get("name").asText());
            assertEquals("tool_calls", chat.get("choices").get(0).get("finish_reason").asText());
        }

        @Test
        @DisplayName("Responses 响应非文本可见内容和工具 payload 不降级到 Chat")
        void nonTextResponsesResponseContentAndToolPayloadIgnoredInChat() throws Exception {
            String body = """
                    {
                        "id": "resp_non_text_payload",
                        "model": "gpt-4o",
                        "output": [
                            {"type":"message","content":[
                                {"type":"output_text","text":123},
                                {"type":"refusal","refusal":false}
                            ]},
                            {"type":"function_call","call_id":"call_func","name":"fn","arguments":{"x":1}},
                            {"type":"custom_tool_call","call_id":"call_custom","name":"grammar","input":["raw"]}
                        ]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.responseFromIR(JSON.readTree(body)));
            JsonNode message = chat.get("choices").get(0).get("message");
            JsonNode toolCalls = message.get("tool_calls");

            assertEquals("", message.get("content").asText());
            assertEquals(2, toolCalls.size());
            assertEquals("{}", toolCalls.get(0).get("function").get("arguments").asText());
            assertEquals("", toolCalls.get(1).get("custom").get("input").asText());
            assertFalse(message.toString().contains("123"));
        }

        @Test
        @DisplayName("reasoning → reasoning_content")
        void reasoning() throws Exception {
            String body = """
                    {
                        "id": "resp_789",
                        "status": "completed",
                        "output": [
                            {"type":"reasoning","summary":[{"type":"summary_text","text":"I thought about it."}]},
                            {"type":"message","content":[{"type":"output_text","text":"The answer is 42."}]}
                        ]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode chat = JSON.readTree(result);

            JsonNode message = chat.get("choices").get(0).get("message");
            assertEquals("The answer is 42.", message.get("content").asText());
            assertEquals("I thought about it.", message.get("reasoning_content").asText());
        }

        @Test
        @DisplayName("reasoning.content 优先于 summary 转为 reasoning_content")
        void reasoningContentPreferredOverSummary() throws Exception {
            String body = """
                    {
                        "id": "resp_reasoning_content",
                        "status": "completed",
                        "output": [
                            {
                                "type":"reasoning",
                                "content":[{"type":"reasoning_text","text":"Full reasoning text."}],
                                "summary":[{"type":"summary_text","text":"Short summary."}]
                            },
                            {"type":"message","content":[{"type":"output_text","text":"Final."}]}
                        ]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode chat = JSON.readTree(result);

            JsonNode message = chat.get("choices").get(0).get("message");
            assertEquals("Final.", message.get("content").asText());
            assertEquals("Full reasoning text.", message.get("reasoning_content").asText());
        }

        @Test
        @DisplayName("多个 message/reasoning output item 聚合为 Chat 单条消息")
        void multipleMessageAndReasoningItemsAggregate() throws Exception {
            String body = """
                    {
                        "id": "resp_multi",
                        "status": "completed",
                        "output": [
                            {"type":"reasoning","summary":[{"type":"summary_text","text":"First thought."}]},
                            {"type":"message","content":[{"type":"output_text","text":"First answer."}]},
                            {"type":"reasoning","summary":[{"type":"summary_text","text":"Second thought."}]},
                            {"type":"message","content":[{"type":"output_text","text":"Second answer."}]}
                        ]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode chat = JSON.readTree(result);
            JsonNode message = chat.get("choices").get(0).get("message");

            assertEquals("First answer.\nSecond answer.", message.get("content").asText());
            assertEquals("First thought.\nSecond thought.", message.get("reasoning_content").asText());
        }

        @Test
        @DisplayName("incomplete → length")
        void incomplete() throws Exception {
            String body = """
                    {
                        "id": "resp_inc",
                        "status": "incomplete",
                        "incomplete_details": {"reason": "max_output_tokens"},
                        "output": [{"type":"message","content":[{"type":"output_text","text":"partial..."}]}]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode chat = JSON.readTree(result);

            assertEquals("length", chat.get("choices").get(0).get("finish_reason").asText());
        }

        @Test
        @DisplayName("incomplete without reason → length")
        void incompleteWithoutReason() throws Exception {
            String body = """
                    {
                        "id": "resp_inc_context",
                        "status": "incomplete",
                        "output": [{"type":"message","content":[{"type":"output_text","text":"partial..."}]}]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode chat = JSON.readTree(result);

            assertEquals("length", chat.get("choices").get(0).get("finish_reason").asText());
        }

        @Test
        @DisplayName("incomplete content_filter → content_filter")
        void incompleteContentFilter() throws Exception {
            String body = """
                    {
                        "id": "resp_filter",
                        "status": "incomplete",
                        "incomplete_details": {"reason": "content_filter"},
                        "output": [{"type":"message","content":[{"type":"output_text","text":""}]}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.responseFromIR(JSON.readTree(body)));

            assertEquals("content_filter", chat.get("choices").get(0).get("finish_reason").asText());
        }

        @Test
        @DisplayName("非文本 Responses status/reason 不降级为 Chat incomplete")
        void nonTextResponsesStatusAndReasonIgnoredInChatFinishReason() throws Exception {
            String body = """
                    {
                        "id": {"value": "resp_non_text_status"},
                        "status": {"value": "incomplete"},
                        "incomplete_details": {"reason": {"value": "max_output_tokens"}},
                        "output": [{"type":"message","content":[{"type":"output_text","text":"fallback"}]}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.responseFromIR(JSON.readTree(body)));

            assertTrue(chat.get("id").asText().startsWith("chatcmpl-"));
            assertEquals("stop", chat.get("choices").get(0).get("finish_reason").asText());
        }

        @Test
        @DisplayName("cached_tokens → prompt_tokens_details")
        void cachedTokens() throws Exception {
            String body = """
                    {
                        "id": "resp_cache",
                        "status": "completed",
                        "output": [{"type":"message","content":[{"type":"output_text","text":"cached"}]}],
                        "usage": {
                            "input_tokens": 100,
                            "output_tokens": 10,
                            "total_tokens": 110,
                            "input_tokens_details": {"cached_tokens": 80}
                        }
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode chat = JSON.readTree(result);
            JsonNode usage = chat.get("usage");

            assertNotNull(usage.get("prompt_tokens_details"));
            assertEquals(80, usage.get("prompt_tokens_details").get("cached_tokens").asInt());
        }

        @Test
        @DisplayName("output_tokens_details.reasoning_tokens → completion_tokens_details")
        void reasoningTokensToChatUsage() throws Exception {
            String body = """
                    {
                        "id": "resp_reasoning_usage",
                        "status": "completed",
                        "output": [{"type":"message","content":[{"type":"output_text","text":"answer"}]}],
                        "usage": {
                            "input_tokens": 100,
                            "output_tokens": 30,
                            "total_tokens": 130,
                            "output_tokens_details": {"reasoning_tokens": 12}
                        }
                    }""";

            JsonNode chat = JSON.readTree(fromIR.responseFromIR(JSON.readTree(body)));
            JsonNode details = chat.get("usage").get("completion_tokens_details");

            assertNotNull(details);
            assertEquals(12, details.get("reasoning_tokens").asInt());
        }

        @Test
        @DisplayName("web_search_call 丢弃")
        void webSearch() throws Exception {
            String body = """
                    {
                        "id": "resp_ws",
                        "status": "completed",
                        "output": [
                            {"type":"web_search_call","action":{"type":"search","query":"test"}},
                            {"type":"message","content":[{"type":"output_text","text":"search results"}]}
                        ]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode chat = JSON.readTree(result);

            assertEquals("stop", chat.get("choices").get(0).get("finish_reason").asText());
            assertEquals("search results", chat.get("choices").get(0).get("message").get("content").asText());
        }

        @Test
        @DisplayName("Responses hosted tool call 输出不降级到 Chat 文本或 tool_calls")
        void hostedToolCallsAreIgnored() throws Exception {
            String body = """
                    {
                        "id": "resp_hosted_tools",
                        "status": "completed",
                        "output": [
                            {"type":"file_search_call","id":"fs_1","status":"completed"},
                            {"type":"computer_call","id":"cu_1","status":"completed"},
                            {"type":"code_interpreter_call","id":"ci_1","status":"completed"},
                            {"type":"mcp_call","id":"mcp_1","status":"completed"},
                            {"type":"image_generation_call","id":"img_1","status":"completed"},
                            {"type":"local_shell_call","id":"sh_1","status":"completed"},
                            {"type":"message","content":[{"type":"output_text","text":"visible answer"}]}
                        ]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode message = JSON.readTree(result).get("choices").get(0).get("message");

            assertEquals("visible answer", message.get("content").asText());
            assertFalse(message.has("tool_calls"));
        }
    }

    // ========================
    // Chat Completions → Responses 响应转换 (toIR.responseToIR)
    // ========================

    @Nested
    @DisplayName("Chat Completions → Responses 响应转换")
    class ChatCompletionsToResponsesResponse {

        @Test
        @DisplayName("基本文本响应")
        void basicTextResponse() throws Exception {
            String body = """
                    {
                        "id": "chatcmpl-123",
                        "object": "chat.completion",
                        "created": 1710000123,
                        "model": "gpt-4o",
                        "choices": [{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}],
                        "usage": {"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}
                    }""";

            JsonNode result = toIR.responseToIR(body);

            assertEquals("chatcmpl-123", result.get("id").asText());
            assertEquals("response", result.get("object").asText());
            assertEquals(1710000123L, result.get("created_at").asLong());
            assertEquals("completed", result.get("status").asText());

            JsonNode output = result.get("output");
            assertEquals(1, output.size());
            assertEquals("message", output.get(0).get("type").asText());
            assertEquals("Hello!", output.get(0).get("content").get(0).get("text").asText());

            JsonNode usage = result.get("usage");
            assertEquals(10, usage.get("input_tokens").asInt());
            assertEquals(5, usage.get("output_tokens").asInt());
        }

        @Test
        @DisplayName("非文本 model/非法 created 不写入 Responses 响应元数据")
        void invalidResponseModelAndCreatedIgnoredInResponsesMetadata() throws Exception {
            String body = """
                    {
                        "id": {"value": "chatcmpl-invalid-meta"},
                        "object": "chat.completion",
                        "created": "1710000123",
                        "model": {"id": "gpt-4o"},
                        "choices": [{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}]
                    }""";

            JsonNode result = toIR.responseToIR(body);

            assertTrue(result.get("id").asText().startsWith("resp_"));
            assertEquals("unknown", result.get("model").asText());
            assertFalse(result.has("created_at"));
        }

        @Test
        @DisplayName("非法 Chat usage token 不写入 Responses usage")
        void invalidChatUsageTokensIgnoredInResponsesUsage() throws Exception {
            String body = """
                    {
                        "id": "chatcmpl-bad-usage",
                        "object": "chat.completion",
                        "model": "gpt-4o",
                        "choices": [{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}],
                        "usage": {
                            "prompt_tokens": "10",
                            "completion_tokens": -5,
                            "total_tokens": {"value": 15},
                            "prompt_tokens_details":{"cached_tokens":"8"},
                            "completion_tokens_details":{"reasoning_tokens":-1}
                        }
                    }""";

            JsonNode result = toIR.responseToIR(body);
            JsonNode usage = result.get("usage");

            assertEquals(0, usage.get("input_tokens").asInt());
            assertEquals(0, usage.get("output_tokens").asInt());
            assertEquals(0, usage.get("total_tokens").asInt());
            assertFalse(usage.has("input_tokens_details"));
            assertFalse(usage.has("output_tokens_details"));
        }

        @Test
        @DisplayName("completion_tokens_details.reasoning_tokens → output_tokens_details")
        void reasoningTokensToResponsesUsage() throws Exception {
            String body = """
                    {
                        "id": "chatcmpl-reasoning-usage",
                        "object": "chat.completion",
                        "model": "gpt-4o",
                        "choices": [{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}],
                        "usage": {
                            "prompt_tokens":10,
                            "completion_tokens":20,
                            "total_tokens":30,
                            "completion_tokens_details":{"reasoning_tokens":8}
                        }
                    }""";

            JsonNode result = toIR.responseToIR(body);
            JsonNode details = result.get("usage").get("output_tokens_details");

            assertNotNull(details);
            assertEquals(8, details.get("reasoning_tokens").asInt());
        }

        @Test
        @DisplayName("reasoning_content → reasoning output item")
        void reasoningContent() throws Exception {
            String body = """
                    {
                        "id": "chatcmpl-456",
                        "object": "chat.completion",
                        "model": "gpt-4o",
                        "choices": [{"index":0,
                            "message":{"role":"assistant","content":"42","reasoning_content":"I counted."},
                            "finish_reason":"stop"}]
                    }""";

            JsonNode result = toIR.responseToIR(body);
            JsonNode output = result.get("output");

            assertEquals(2, output.size());
            assertEquals("reasoning", output.get(0).get("type").asText());
            assertEquals("I counted.", output.get(0).get("summary").get(0).get("text").asText());
            assertEquals("message", output.get(1).get("type").asText());
        }

        @Test
        @DisplayName("refusal → Responses refusal content part")
        void refusalContent() throws Exception {
            String body = """
                    {
                        "id": "chatcmpl-refusal",
                        "object": "chat.completion",
                        "model": "gpt-4o",
                        "choices": [{"index":0,
                            "message":{"role":"assistant","content":null,"refusal":"I cannot help with that."},
                            "finish_reason":"stop"}]
                    }""";

            JsonNode result = toIR.responseToIR(body);
            JsonNode part = result.get("output").get(0).get("content").get(0);

            assertEquals("refusal", part.get("type").asText());
            assertEquals("I cannot help with that.", part.get("refusal").asText());
        }

        @Test
        @DisplayName("tool_calls → function_call items")
        void toolCallsInResponse() throws Exception {
            String body = """
                    {
                        "id": "chatcmpl-789",
                        "object": "chat.completion",
                        "model": "gpt-4o",
                        "choices": [{"index":0,
                            "message":{"role":"assistant","content":"","tool_calls":[
                                {"id":"call_1","type":"function","function":{"name":"ping","arguments":"{\\"host\\":\\"x\\"}"}}
                            ]},
                            "finish_reason":"tool_calls"}],
                        "usage": {"prompt_tokens":5,"completion_tokens":10,"total_tokens":15}
                    }""";

            JsonNode result = toIR.responseToIR(body);
            JsonNode output = result.get("output");

            assertEquals(1, output.size());
            assertEquals("function_call", output.get(0).get("type").asText());
            assertEquals("call_1", output.get(0).get("call_id").asText());
            assertEquals("ping", output.get(0).get("name").asText());
        }

        @Test
        @DisplayName("Chat custom tool_call 响应 → custom_tool_call item")
        void customToolCallInResponse() throws Exception {
            String body = """
                    {
                        "id": "chatcmpl-custom",
                        "object": "chat.completion",
                        "model": "gpt-4o",
                        "choices": [{"index":0,
                            "message":{"role":"assistant","content":"","tool_calls":[
                                {"id":"call_custom_1","type":"custom","custom":{"name":"grammar","input":"start: expr"}}
                            ]},
                            "finish_reason":"tool_calls"}]
                    }""";

            JsonNode result = toIR.responseToIR(body);
            JsonNode output = result.get("output");

            assertEquals(1, output.size());
            assertEquals("custom_tool_call", output.get(0).get("type").asText());
            assertEquals("call_custom_1", output.get(0).get("call_id").asText());
            assertEquals("grammar", output.get(0).get("name").asText());
            assertEquals("start: expr", output.get(0).get("input").asText());
        }

        @Test
        @DisplayName("旧式 function_call 响应 → function_call item")
        void legacyFunctionCallInResponse() throws Exception {
            String body = """
                    {
                        "id": "chatcmpl-legacy-fn",
                        "object": "chat.completion",
                        "model": "gpt-4o",
                        "choices": [{"index":0,
                            "message":{"role":"assistant","content":null,
                                "function_call":{"name":"get_weather","arguments":"{\\"city\\":\\"NYC\\"}"}},
                            "finish_reason":"function_call"}]
                    }""";

            JsonNode result = toIR.responseToIR(body);
            JsonNode output = result.get("output");

            assertEquals("function_call", output.get(0).get("type").asText());
            assertEquals("get_weather", output.get(0).get("name").asText());
            assertEquals("{\"city\":\"NYC\"}", output.get(0).get("arguments").asText());
        }

        @Test
        @DisplayName("Chat 响应缺 id/name 的工具结构不进入 Responses IR")
        void invalidToolShapesInResponseIgnored() throws Exception {
            String body = """
                    {
                        "id": "chatcmpl-invalid-tools",
                        "object": "chat.completion",
                        "model": "gpt-4o",
                        "choices": [{"index":0,
                            "message":{"role":"assistant","content":"","tool_calls":[
                                {"type":"function","function":{"name":"missing_id","arguments":"{}"}},
                                {"id":"call_missing_name","type":"function","function":{"arguments":"{}"}},
                                {"id":"call_custom_missing_name","type":"custom","custom":{"input":"raw"}}
                            ]},
                            "finish_reason":"tool_calls"}]
                    }""";

            JsonNode result = toIR.responseToIR(body);
            JsonNode output = result.get("output");

            assertEquals(0, output.size());
            assertFalse(output.toString().contains("function_call"));
            assertFalse(output.toString().contains("custom_tool_call"));
        }

        @Test
        @DisplayName("Chat 响应非文本可见内容和工具 payload 不进入 Responses IR")
        void nonTextResponseVisibleContentAndToolPayloadIgnoredInResponsesIR() throws Exception {
            String body = """
                    {
                        "id": "chatcmpl-non-text-payload",
                        "object": "chat.completion",
                        "model": "gpt-4o",
                        "choices": [{"index":0,
                            "message":{
                                "role":"assistant",
                                "content":123,
                                "refusal":false,
                                "reasoning_content":{"text":"hidden"},
                                "tool_calls":[
                                    {"id":"call_func","type":"function","function":{"name":"fn","arguments":{"x":1}}},
                                    {"id":"call_custom","type":"custom","custom":{"name":"grammar","input":["raw"]}}
                                ]
                            },
                            "finish_reason":"tool_calls"}]
                    }""";

            JsonNode result = toIR.responseToIR(body);
            JsonNode output = result.get("output");

            assertEquals(2, output.size());
            assertEquals("function_call", output.get(0).get("type").asText());
            assertEquals("{}", output.get(0).get("arguments").asText());
            assertEquals("custom_tool_call", output.get(1).get("type").asText());
            assertEquals("", output.get(1).get("input").asText());
            assertFalse(output.toString().contains("message"));
            assertFalse(output.toString().contains("reasoning"));
            assertFalse(output.toString().contains("hidden"));
        }

        @Test
        @DisplayName("Chat 空 assistant 响应不伪造 Responses 空 output_text")
        void emptyAssistantResponseDoesNotCreateOutputText() throws Exception {
            String body = """
                    {
                        "id": "chatcmpl-empty-message",
                        "object": "chat.completion",
                        "model": "gpt-4o",
                        "choices": [{"index":0,"message":{"role":"assistant","content":""},"finish_reason":"stop"}]
                    }""";

            JsonNode result = toIR.responseToIR(body);

            assertEquals("completed", result.get("status").asText());
            assertEquals(0, result.get("output").size());
        }

        @Test
        @DisplayName("length → incomplete")
        void lengthFinishReason() throws Exception {
            String body = """
                    {
                        "id": "chatcmpl-len",
                        "object": "chat.completion",
                        "model": "gpt-4o",
                        "choices": [{"index":0,"message":{"role":"assistant","content":"partial..."},"finish_reason":"length"}]
                    }""";

            JsonNode result = toIR.responseToIR(body);
            assertEquals("incomplete", result.get("status").asText());
            assertEquals("max_output_tokens", result.get("incomplete_details").get("reason").asText());
        }

        @Test
        @DisplayName("content_filter → incomplete content_filter")
        void contentFilterFinishReason() throws Exception {
            String body = """
                    {
                        "id": "chatcmpl-filter",
                        "object": "chat.completion",
                        "model": "gpt-4o",
                        "choices": [{"index":0,"message":{"role":"assistant","content":""},"finish_reason":"content_filter"}]
                    }""";

            JsonNode result = toIR.responseToIR(body);

            assertEquals("incomplete", result.get("status").asText());
            assertEquals("content_filter", result.get("incomplete_details").get("reason").asText());
            assertEquals(0, result.get("output").size());
        }

        @Test
        @DisplayName("非文本 Chat finish_reason 不写成 Responses incomplete")
        void nonTextChatFinishReasonIgnoredInResponsesStatus() throws Exception {
            String body = """
                    {
                        "id": "chatcmpl-non-text-finish",
                        "object": "chat.completion",
                        "model": "gpt-4o",
                        "choices": [{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":{"value":"length"}}]
                    }""";

            JsonNode result = toIR.responseToIR(body);

            assertEquals("completed", result.get("status").asText());
            assertFalse(result.has("incomplete_details"));
        }

        @Test
        @DisplayName("无 choices → 空 output")
        void noChoices() throws Exception {
            String body = """
                    {"id":"chatcmpl-empty","object":"chat.completion","model":"gpt-4o","choices":[]}""";

            JsonNode result = toIR.responseToIR(body);
            assertEquals("completed", result.get("status").asText());
            assertEquals(0, result.get("output").size());
        }
    }

    // ========================
    // Responses → Chat 请求转换
    // ========================

    @Nested
    @DisplayName("Responses IR → Chat Completions 请求转换")
    class ResponsesToChatCompletionsRequest {

        @Test
        @DisplayName("instructions → system message")
        void instructionsToSystem() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "instructions": "You are helpful.",
                        "input": [{"role":"user","content":"Hi"}]
                    }""";

            JsonNode ir = JSON.readTree(body);
            String result = fromIR.requestFromIR(ir);
            JsonNode chat = JSON.readTree(result);

            JsonNode messages = chat.get("messages");
            assertEquals(2, messages.size());
            assertEquals("system", messages.get(0).get("role").asText());
            assertEquals("You are helpful.", messages.get(0).get("content").asText());
        }

        @Test
        @DisplayName("空白 instructions 不生成 Chat system message")
        void blankInstructionsDoNotCreateSystemMessage() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "instructions": "   ",
                        "input": [{"role":"user","content":"Hi"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode messages = chat.get("messages");

            assertEquals(1, messages.size());
            assertEquals("user", messages.get(0).get("role").asText());
            assertEquals("Hi", messages.get(0).get("content").asText());
        }

        @Test
        @DisplayName("Responses 字符串 input → Chat user message")
        void stringInputToUserMessage() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": "Hello"
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode messages = chat.get("messages");

            assertEquals(1, messages.size());
            assertEquals("user", messages.get(0).get("role").asText());
            assertEquals("Hello", messages.get(0).get("content").asText());
        }

        @Test
        @DisplayName("Responses input refusal part → Chat 文本兼容内容")
        void refusalInputPartToChatText() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [{
                            "role": "assistant",
                            "content": [
                                {"type":"output_text","text":"Visible text"},
                                {"type":"refusal","refusal":"I cannot help with that."}
                            ]
                        }]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode message = chat.get("messages").get(0);

            assertEquals("assistant", message.get("role").asText());
            assertEquals("Visible text\nI cannot help with that.", message.get("content").asText());
        }

        @Test
        @DisplayName("Responses 空 text/refusal part 不生成 Chat 空消息")
        void emptyTextPartsDoNotCreateChatMessage() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [
                            {"role": "user", "content": [
                                {"type":"input_text","text":""},
                                {"type":"text","text":"   "},
                                {"type":"refusal","refusal":""}
                            ]},
                            {"role": "user", "content": [
                                {"type":"input_text","text":"visible"}
                            ]}
                        ]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode messages = chat.get("messages");

            assertEquals(1, messages.size());
            assertEquals("user", messages.get(0).get("role").asText());
            assertEquals("visible", messages.get(0).get("content").asText());
            assertFalse(messages.toString().contains("\"content\":\"\""));
        }

        @Test
        @DisplayName("Responses 非字符串/数组 message content 不生成 Chat 空消息")
        void unsupportedMessageContentDoesNotCreateChatMessage() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [
                            {"role": "user", "content": {"type":"unsupported"}},
                            {"role": "user", "content": "visible"}
                        ]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode messages = chat.get("messages");

            assertEquals(1, messages.size());
            assertEquals("user", messages.get(0).get("role").asText());
            assertEquals("visible", messages.get(0).get("content").asText());
        }

        @Test
        @DisplayName("store 保留到 Chat Completions")
        void storePreserved() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "store": true,
                        "input": [{"role":"user","content":"Hi"}]
                    }""";

            JsonNode ir = JSON.readTree(body);
            String result = fromIR.requestFromIR(ir);
            JsonNode chat = JSON.readTree(result);

            assertTrue(chat.get("store").asBoolean());
        }

        @Test
        @DisplayName("Responses 官方同义字段保留到 Chat")
        void officialSharedFieldsToChat() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "top_logprobs": 5,
                        "include": ["message.output_text.logprobs"],
                        "safety_identifier": "user_456",
                        "prompt_cache_key": "tenant:thread",
                        "text": {"verbosity": "high"},
                        "tools": [{
                            "type": "web_search_preview",
                            "search_context_size": "medium",
                            "user_location": {
                                "type": "approximate",
                                "country": "US",
                                "region": "WA",
                                "city": "Seattle",
                                "timezone": "America/Los_Angeles"
                            }
                        }],
                        "input": [{"role":"user","content":"Hi"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertTrue(chat.get("logprobs").asBoolean());
            assertEquals(5, chat.get("top_logprobs").asInt());
            assertEquals("user_456", chat.get("safety_identifier").asText());
            assertEquals("tenant:thread", chat.get("prompt_cache_key").asText());
            assertEquals("high", chat.get("verbosity").asText());
            assertEquals("medium", chat.get("web_search_options").get("search_context_size").asText());
            assertEquals("Seattle", chat.get("web_search_options").get("user_location").get("approximate").get("city").asText());
        }

        @Test
        @DisplayName("非法 top_logprobs 不降级到 Chat")
        void invalidTopLogprobsIgnoredInChat() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "top_logprobs": 21,
                        "include": ["message.output_text.logprobs"],
                        "input": [{"role":"user","content":"Hi"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertTrue(chat.get("logprobs").asBoolean());
            assertFalse(chat.has("top_logprobs"));
        }

        @Test
        @DisplayName("非法 Responses web_search 子字段不降级到 Chat")
        void invalidResponsesWebSearchFieldsIgnoredInChat() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "tools": [{
                            "type": "web_search_preview",
                            "search_context_size": "huge",
                            "user_location": {
                                "type": "approximate",
                                "country": 123,
                                "city": "",
                                "timezone": {"name": "America/Los_Angeles"}
                            }
                        }],
                        "input": [{"role":"user","content":"Hi"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode webSearchOptions = chat.get("web_search_options");

            assertNotNull(webSearchOptions);
            assertFalse(webSearchOptions.has("search_context_size"));
            assertFalse(webSearchOptions.has("user_location"));
        }

        @Test
        @DisplayName("非法 reasoning.effort/text.verbosity 不降级到 Chat")
        void invalidReasoningEffortAndVerbosityIgnoredInChat() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "reasoning": {"effort": "extreme"},
                        "text": {"verbosity": "chatty"},
                        "input": [{"role":"user","content":"Hi"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertFalse(chat.has("reasoning_effort"));
            assertFalse(chat.has("verbosity"));
        }

        @Test
        @DisplayName("非法 boolean/numeric 基础字段不降级到 Chat")
        void invalidScalarFieldsIgnoredInChat() throws Exception {
            String body = """
                    {
                        "model": {"id": "gpt-4o"},
                        "stream": "yes",
                        "store": "no",
                        "parallel_tool_calls": "true",
                        "temperature": "hot",
                        "top_p": {"value": 1},
                        "max_output_tokens": "many",
                        "input": [{"role":"user","content":"Hi"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertFalse(chat.has("model"));
            assertFalse(chat.has("stream"));
            assertFalse(chat.has("stream_options"));
            assertFalse(chat.has("store"));
            assertFalse(chat.has("parallel_tool_calls"));
            assertFalse(chat.has("temperature"));
            assertFalse(chat.has("top_p"));
            assertFalse(chat.has("max_completion_tokens"));
        }

        @Test
        @DisplayName("function_call_input → assistant with tool_calls")
        void functionCallInput() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [
                            {"role":"user","content":"Hello"},
                            {"type":"function_call","call_id":"call_1","name":"get_weather","arguments":"{\\"city\\":\\"NYC\\"}"}
                        ]
                    }""";

            JsonNode ir = JSON.readTree(body);
            String result = fromIR.requestFromIR(ir);
            JsonNode chat = JSON.readTree(result);

            JsonNode messages = chat.get("messages");
            assertEquals(2, messages.size());

            // 第二个消息应该是 assistant with tool_calls
            JsonNode assistantMsg = messages.get(1);
            assertEquals("assistant", assistantMsg.get("role").asText());
            JsonNode toolCalls = assistantMsg.get("tool_calls");
            assertEquals(1, toolCalls.size());
            assertEquals("call_1", toolCalls.get(0).get("id").asText());
            assertEquals("get_weather", toolCalls.get(0).get("function").get("name").asText());
        }

        @Test
        @DisplayName("Responses custom tool/call → Chat custom tool/call")
        void customToolRequestFromIR() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "tools": [{
                            "type": "custom",
                            "name": "grammar",
                            "description": "Generate by grammar",
                            "format": {"type": "grammar", "syntax": "lark", "definition": "start: expr"}
                        }],
                        "tool_choice": {"type": "custom", "name": "grammar"},
                        "input": [
                            {"role":"user","content":"Use grammar"},
                            {"type":"custom_tool_call","call_id":"call_custom_1","name":"grammar","input":"start: expr"}
                        ]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            JsonNode tool = chat.get("tools").get(0);
            assertEquals("custom", tool.get("type").asText());
            assertEquals("grammar", tool.get("custom").get("name").asText());
            assertEquals("lark", tool.get("custom").get("format").get("grammar").get("syntax").asText());
            assertEquals("start: expr", tool.get("custom").get("format").get("grammar").get("definition").asText());

            JsonNode tc = chat.get("tool_choice");
            assertEquals("custom", tc.get("type").asText());
            assertEquals("grammar", tc.get("custom").get("name").asText());

            JsonNode customCall = chat.get("messages").get(1).get("tool_calls").get(0);
            assertEquals("custom", customCall.get("type").asText());
            assertEquals("start: expr", customCall.get("custom").get("input").asText());
        }

        @Test
        @DisplayName("Responses custom tool 非官方 format 不降级到 Chat")
        void invalidResponsesCustomToolFormatIgnoredFromIR() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "tools": [
                            {"type":"custom","name":"nested","format":{"type":"grammar","grammar":{"syntax":"lark","definition":"start: expr"}}},
                            {"type":"custom","name":"bad_syntax","format":{"type":"grammar","syntax":"peg","definition":"start: expr"}},
                            {"type":"custom","name":"non_object","format":"grammar"}
                        ],
                        "input": [{"role":"user","content":"Use grammar"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            JsonNode tools = chat.get("tools");
            assertEquals(3, tools.size());
            assertFalse(tools.get(0).get("custom").has("format"));
            assertFalse(tools.get(1).get("custom").has("format"));
            assertFalse(tools.get(2).get("custom").has("format"));
        }

        @Test
        @DisplayName("缺 name 的 Responses tools 不降级到 Chat tools")
        void unnamedResponsesToolsDroppedFromIR() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "tools": [
                            {"type":"function","parameters":{"type":"object"}},
                            {"type":"custom","format":{"type":"text"}}
                        ],
                        "input": [{"role":"user","content":"Use tools"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertFalse(chat.has("tools"));
        }

        @Test
        @DisplayName("Responses function parameters 非对象 → Chat 空 object schema")
        void nonObjectToolParametersUseEmptySchemaFromIR() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "tools": [
                            {"type":"function","name":"lookup","parameters":"not-a-schema"}
                        ],
                        "input": [{"role":"user","content":"Use tools"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode parameters = chat.get("tools").get(0).get("function").get("parameters");

            assertEquals("object", parameters.get("type").asText());
            assertTrue(parameters.get("properties").isObject());
        }

        @Test
        @DisplayName("Responses allowed_tools tool_choice → Chat 嵌套 allowed_tools")
        void allowedToolsToolChoiceFromIR() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "tool_choice": {
                            "type": "allowed_tools",
                            "mode": "auto",
                            "tools": [
                                {"type": "function", "name": "get_weather"},
                                {"type": "custom", "name": "grammar"}
                            ]
                        },
                        "input": [{"role":"user","content":"Use one tool"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode allowed = chat.get("tool_choice").get("allowed_tools");

            assertEquals("auto", allowed.get("mode").asText());
            assertEquals("function", allowed.get("tools").get(0).get("type").asText());
            assertEquals("get_weather", allowed.get("tools").get(0).get("function").get("name").asText());
            assertEquals("custom", allowed.get("tools").get(1).get("type").asText());
            assertEquals("grammar", allowed.get("tools").get(1).get("custom").get("name").asText());
        }

        @Test
        @DisplayName("Responses allowed_tools 中 unsupported tool 不透传到 Chat")
        void allowedToolsDropsUnsupportedToolsFromIR() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "tool_choice": {
                            "type": "allowed_tools",
                            "mode": "auto",
                            "tools": [
                                {"type": "function", "name": "get_weather"},
                                {"type": "file_search", "name": "knowledge"}
                            ]
                        },
                        "input": [{"role":"user","content":"Use one tool"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode tools = chat.get("tool_choice").get("allowed_tools").get("tools");

            assertEquals(1, tools.size());
            assertEquals("function", tools.get(0).get("type").asText());
            assertEquals("get_weather", tools.get(0).get("function").get("name").asText());
        }

        @Test
        @DisplayName("Responses allowed_tools 非法 mode 不降级到 Chat")
        void invalidAllowedToolsModeDroppedFromIR() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "tool_choice": {
                            "type": "allowed_tools",
                            "mode": "sometimes",
                            "tools": [
                                {"type": "function", "name": "get_weather"}
                            ]
                        },
                        "input": [{"role":"user","content":"Use one tool"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode allowed = chat.get("tool_choice").get("allowed_tools");

            assertFalse(allowed.has("mode"));
            assertEquals("get_weather", allowed.get("tools").get(0).get("function").get("name").asText());
        }

        @Test
        @DisplayName("unsupported Responses tool_choice 不透传到 Chat")
        void unsupportedToolChoiceDroppedFromIR() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "tool_choice": {"type": "file_search", "name": "knowledge"},
                        "input": [{"role":"user","content":"Use search"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertFalse(chat.has("tool_choice"));
        }

        @Test
        @DisplayName("未知文本 Responses tool_choice 不透传到 Chat")
        void unknownTextToolChoiceDroppedFromIR() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "tool_choice": "file_search",
                        "input": [{"role":"user","content":"Use search"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertFalse(chat.has("tool_choice"));
        }

        @Test
        @DisplayName("缺 name 的 Responses tool_choice 不透传到 Chat")
        void unnamedToolChoiceDroppedFromIR() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "tool_choice": {"type": "custom"},
                        "input": [{"role":"user","content":"Use tool"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertFalse(chat.has("tool_choice"));
        }

        @Test
        @DisplayName("连续 function_call input 合并为单条 assistant tool_calls")
        void consecutiveFunctionCallsInputMerged() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [
                            {"role":"user","content":"Do both"},
                            {"type":"function_call","call_id":"call_1","name":"get_weather","arguments":"{\\"city\\":\\"NYC\\"}"},
                            {"type":"function_call","call_id":"call_2","name":"get_time","arguments":"{\\"tz\\":\\"UTC\\"}"},
                            {"type":"function_call_output","call_id":"call_1","output":"Sunny"},
                            {"type":"function_call_output","call_id":"call_2","output":"10:00"}
                        ]
                    }""";

            JsonNode ir = JSON.readTree(body);
            String result = fromIR.requestFromIR(ir);
            JsonNode chat = JSON.readTree(result);
            JsonNode messages = chat.get("messages");

            assertEquals(4, messages.size());
            JsonNode assistantMsg = messages.get(1);
            assertEquals("assistant", assistantMsg.get("role").asText());
            JsonNode toolCalls = assistantMsg.get("tool_calls");
            assertEquals(2, toolCalls.size());
            assertEquals("call_1", toolCalls.get(0).get("id").asText());
            assertEquals("call_2", toolCalls.get(1).get("id").asText());
            assertEquals("tool", messages.get(2).get("role").asText());
            assertEquals("tool", messages.get(3).get("role").asText());
        }

        @Test
        @DisplayName("Responses tool call 异常形状降级到 Chat 时不构造非法空 id/name")
        void abnormalToolCallShapesToChat() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [
                            {"role":"user","content":"Run tools"},
                            {"type":"function_call_output","output":"early result"},
                            {"type":"function_call","name":"missing_call_id","arguments":""},
                            {"type":"function_call","call_id":"call_raw","name":"raw_args","arguments":"not-json"}
                        ]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode messages = chat.get("messages");

            assertEquals(2, messages.size());
            JsonNode toolCalls = messages.get(1).get("tool_calls");
            assertEquals(1, toolCalls.size());
            assertEquals("call_raw", toolCalls.get(0).get("id").asText());
            assertEquals("raw_args", toolCalls.get(0).get("function").get("name").asText());
            assertEquals("not-json", toolCalls.get(0).get("function").get("arguments").asText());
        }

        @Test
        @DisplayName("Responses 请求非文本可见内容和工具 payload 不降级到 Chat")
        void nonTextResponsesRequestContentAndToolPayloadIgnoredInChat() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [
                            {"role":"user","content":[
                                {"type":"input_text","text":123},
                                {"type":"refusal","refusal":false},
                                {"type":"input_image","image_url":456},
                                {"type":"input_file","file_id":789,"file_data":{"data":"hidden"},"filename":true},
                                {"type":"input_text","text":"visible"}
                            ]},
                            {"type":"function_call","call_id":"call_func","name":"fn","arguments":{"x":1}},
                            {"type":"custom_tool_call","call_id":"call_custom","name":"grammar","input":["raw"]},
                            {"type":"function_call_output","call_id":"call_func","output":{"value":"object output"}}
                        ]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode messages = chat.get("messages");

            assertEquals(3, messages.size());
            assertEquals("visible", messages.get(0).get("content").asText());
            JsonNode toolCalls = messages.get(1).get("tool_calls");
            assertEquals("{}", toolCalls.get(0).get("function").get("arguments").asText());
            assertEquals("", toolCalls.get(1).get("custom").get("input").asText());
            assertEquals("", messages.get(2).get("content").asText());
            assertFalse(messages.toString().contains("hidden"));
            assertFalse(messages.toString().contains("123"));
        }

        @Test
        @DisplayName("function_call_output → tool message")
        void functionCallOutput() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [
                            {"type":"function_call_output","call_id":"call_1","output":"Sunny, 72°F"}
                        ]
                    }""";

            JsonNode ir = JSON.readTree(body);
            String result = fromIR.requestFromIR(ir);
            JsonNode chat = JSON.readTree(result);

            JsonNode messages = chat.get("messages");
            assertEquals(1, messages.size());
            assertEquals("tool", messages.get(0).get("role").asText());
            assertEquals("call_1", messages.get(0).get("tool_call_id").asText());
            assertEquals("Sunny, 72°F", messages.get(0).get("content").asText());
        }

        @Test
        @DisplayName("Responses function_call_output 缺 output → Chat 空 tool content")
        void missingFunctionCallOutputContentStaysEmpty() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [
                            {"type":"function_call_output","call_id":"call_1"}
                        ]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode message = chat.get("messages").get(0);

            assertEquals("tool", message.get("role").asText());
            assertEquals("call_1", message.get("tool_call_id").asText());
            assertEquals("", message.get("content").asText());
        }

        @Test
        @DisplayName("Responses 多模态 input → Chat content parts")
        void multimodalInputToChatParts() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [{"role":"user","content":[
                            {"type":"input_text","text":"Review these"},
                            {"type":"input_image","image_url":"https://example.com/a.png","detail":"low"},
                            {"type":"input_file","file_id":"file_123","filename":"brief.pdf"},
                            {"type":"input_audio","input_audio":{"data":"UklGRg==","format":"wav"}}
                        ]}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode content = chat.get("messages").get(0).get("content");

            assertEquals("text", content.get(0).get("type").asText());
            assertEquals("image_url", content.get(1).get("type").asText());
            assertEquals("https://example.com/a.png", content.get(1).get("image_url").get("url").asText());
            assertEquals("low", content.get(1).get("image_url").get("detail").asText());
            assertEquals("file", content.get(2).get("type").asText());
            assertEquals("file_123", content.get(2).get("file").get("file_id").asText());
            assertEquals("input_audio", content.get(3).get("type").asText());
            assertEquals("wav", content.get(3).get("input_audio").get("format").asText());
        }

        @Test
        @DisplayName("非法 Responses image/file 子字段不降级到 Chat")
        void invalidResponsesImageAndFileFieldsIgnoredInChat() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [{"role":"user","content":[
                            {"type":"input_text","text":"Review these"},
                            {"type":"input_image","image_url":"https://example.com/a.png","detail":{"level":"high"}},
                            {"type":"input_file","file_id":"file_123","filename":{"name":"brief.pdf"}}
                        ]}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode content = chat.get("messages").get(0).get("content");

            assertFalse(content.get(1).get("image_url").has("detail"));
            assertEquals("file_123", content.get(2).get("file").get("file_id").asText());
            assertFalse(content.get(2).get("file").has("filename"));
        }

        @Test
        @DisplayName("Responses input_file.file_url 不伪造成 Chat file part")
        void inputFileUrlDoesNotBecomeChatFilePart() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [{"role":"user","content":[
                            {"type":"input_text","text":"Review this URL document"},
                            {"type":"input_file","file_url":"https://example.com/remote.pdf","filename":"remote.pdf"}
                        ]}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode message = chat.get("messages").get(0);

            assertEquals("Review this URL document", message.get("content").asText());
        }

        @Test
        @DisplayName("Responses unsupported-only content 不伪造成 Chat 空消息")
        void unsupportedOnlyContentDoesNotCreateEmptyChatMessage() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [
                            {"role":"user","content":[
                                {"type":"input_file","file_url":"https://example.com/remote.pdf","filename":"remote.pdf"}
                            ]},
                            {"role":"user","content":[
                                {"type":"input_audio"}
                            ]},
                            {"role":"user","content":[
                                {"type":"input_image","image_url":""}
                            ]},
                            {"role":"user","content":[{"type":"input_text","text":"visible"}]}
                        ]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode messages = chat.get("messages");

            assertEquals(1, messages.size());
            assertEquals("user", messages.get(0).get("role").asText());
            assertEquals("visible", messages.get(0).get("content").asText());
            assertFalse(messages.toString().contains("remote.pdf"));
            assertFalse(messages.toString().contains("input_audio"));
        }

        @Test
        @DisplayName("Responses input_file 缺有效载荷时不生成 Chat file part")
        void inputFileWithoutPayloadDoesNotBecomeChatFilePart() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [{"role":"user","content":[
                            {"type":"input_text","text":"Review these"},
                            {"type":"input_file","filename":"brief.pdf"},
                            {"type":"input_file","file_id":"   ","filename":"blank-id.pdf"},
                            {"type":"input_file","file_data":"","filename":"blank-data.pdf"},
                            {"type":"input_file","file_id":"file_123","filename":"ok.pdf"}
                        ]}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode content = chat.get("messages").get(0).get("content");

            assertEquals(2, content.size());
            assertEquals("text", content.get(0).get("type").asText());
            assertEquals("file", content.get(1).get("type").asText());
            assertEquals("file_123", content.get(1).get("file").get("file_id").asText());
            assertEquals("ok.pdf", content.get(1).get("file").get("filename").asText());
        }

        @Test
        @DisplayName("Responses input_audio 缺 data/format 时不生成 Chat input_audio part")
        void inputAudioWithoutPayloadDoesNotBecomeChatPart() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [{"role":"user","content":[
                            {"type":"input_text","text":"transcribe valid audio only"},
                            {"type":"input_audio","input_audio":{"data":"UklGRg=="}},
                            {"type":"input_audio","input_audio":{"format":"wav"}}
                        ]}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode message = chat.get("messages").get(0);

            assertEquals("transcribe valid audio only", message.get("content").asText());
            assertFalse(message.toString().contains("input_audio"));
            assertFalse(message.toString().contains("UklGRg=="));
        }

        @Test
        @DisplayName("developer role 保留")
        void developerRolePreserved() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [{"role":"developer","content":"You are helpful."}]
                    }""";

            JsonNode ir = JSON.readTree(body);
            String result = fromIR.requestFromIR(ir);
            JsonNode chat = JSON.readTree(result);

            JsonNode messages = chat.get("messages");
            assertEquals(1, messages.size());
            assertEquals("developer", messages.get(0).get("role").asText());
        }

        @Test
        @DisplayName("工具选择嵌套格式转换")
        void toolChoiceConversion() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [{"role":"user","content":"Hello"}],
                        "tool_choice": {"type":"function","name":"get_weather"}
                    }""";

            JsonNode ir = JSON.readTree(body);
            String result = fromIR.requestFromIR(ir);
            JsonNode chat = JSON.readTree(result);

            JsonNode tc = chat.get("tool_choice");
            assertEquals("function", tc.get("type").asText());
            assertEquals("get_weather", tc.get("function").get("name").asText());
        }

        @Test
        @DisplayName("text.format json_schema → response_format")
        void textFormatJsonSchemaToResponseFormat() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [{"role":"user","content":"Return JSON"}],
                        "text": {
                            "format": {
                                "type": "json_schema",
                                "name": "weather_answer",
                                "description": "Weather answer",
                                "strict": true,
                                "schema": {
                                    "type": "object",
                                    "properties": {"city": {"type": "string"}},
                                    "required": ["city"],
                                    "additionalProperties": false
                                }
                            }
                        }
                    }""";

            JsonNode ir = JSON.readTree(body);
            String result = fromIR.requestFromIR(ir);
            JsonNode chat = JSON.readTree(result);
            JsonNode responseFormat = chat.get("response_format");

            assertEquals("json_schema", responseFormat.get("type").asText());
            assertEquals("weather_answer", responseFormat.get("json_schema").get("name").asText());
            assertEquals("Weather answer", responseFormat.get("json_schema").get("description").asText());
            assertTrue(responseFormat.get("json_schema").get("strict").asBoolean());
            assertEquals("object", responseFormat.get("json_schema").get("schema").get("type").asText());
        }

        @Test
        @DisplayName("非 boolean text.format strict 不降级到 Chat response_format")
        void nonBooleanTextFormatStrictIgnoredInChat() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [{"role":"user","content":"Return JSON"}],
                        "text": {
                            "format": {
                                "type": "json_schema",
                                "name": "weather_answer",
                                "description": {"text": "Weather answer"},
                                "strict": "true",
                                "schema": {"type": "object"}
                            }
                        }
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertFalse(chat.get("response_format").get("json_schema").has("description"));
            assertFalse(chat.get("response_format").get("json_schema").has("strict"));
        }

        @Test
        @DisplayName("非 boolean Responses tool strict 不降级到 Chat")
        void nonBooleanResponsesToolStrictIgnoredInChat() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "tools": [{"type":"function","name":"get_weather","description":{"text":"Get weather"},"strict":"true","parameters":{"type":"object"}}],
                        "input": [{"role":"user","content":"Hi"}]
                    }""";

            JsonNode chat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertFalse(chat.get("tools").get(0).get("function").has("description"));
            assertFalse(chat.get("tools").get(0).get("function").has("strict"));
        }

        @Test
        @DisplayName("text.format json_schema 缺 name/schema 不生成 Chat response_format")
        void textFormatJsonSchemaWithoutRequiredFieldsDoesNotCreateResponseFormat() throws Exception {
            String missingNameBody = """
                    {
                        "model": "gpt-4o",
                        "input": [{"role":"user","content":"Return JSON"}],
                        "text": {
                            "format": {
                                "type": "json_schema",
                                "schema": {"type": "object"}
                            }
                        }
                    }""";
            String missingSchemaBody = """
                    {
                        "model": "gpt-4o",
                        "input": [{"role":"user","content":"Return JSON"}],
                        "text": {
                            "format": {
                                "type": "json_schema",
                                "name": "weather_answer"
                            }
                        }
                    }""";

            JsonNode missingName = JSON.readTree(fromIR.requestFromIR(JSON.readTree(missingNameBody)));
            JsonNode missingSchema = JSON.readTree(fromIR.requestFromIR(JSON.readTree(missingSchemaBody)));

            assertFalse(missingName.has("response_format"));
            assertFalse(missingSchema.has("response_format"));
        }

        @Test
        @DisplayName("text.format json_object/text → response_format")
        void textFormatSimpleTypesToResponseFormat() throws Exception {
            String jsonObjectBody = """
                    {
                        "model": "gpt-4o",
                        "input": [{"role":"user","content":"Return JSON"}],
                        "text": {"format": {"type": "json_object"}}
                    }""";
            String textBody = """
                    {
                        "model": "gpt-4o",
                        "input": [{"role":"user","content":"Return text"}],
                        "text": {"format": {"type": "text"}}
                    }""";

            JsonNode jsonObjectChat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(jsonObjectBody)));
            JsonNode textChat = JSON.readTree(fromIR.requestFromIR(JSON.readTree(textBody)));

            assertEquals("json_object", jsonObjectChat.get("response_format").get("type").asText());
            assertEquals("text", textChat.get("response_format").get("type").asText());
        }

        @Test
        @DisplayName("developer role input 转回 Chat developer message")
        void developerInputRolePreserved() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "input": [
                            {"role":"developer","content":"Project rules."},
                            {"role":"user","content":"Hi"}
                        ]
                    }""";

            JsonNode ir = JSON.readTree(body);
            JsonNode chat = JSON.readTree(fromIR.requestFromIR(ir));

            assertEquals("developer", chat.get("messages").get(0).get("role").asText());
            assertEquals("Project rules.", chat.get("messages").get(0).get("content").asText());
        }
    }

    // ========================
    // 流式 Chat SSE → Responses IR SSE
    // ========================

    @Nested
    @DisplayName("流式 Chat Completions SSE → Responses IR SSE")
    class ChatToResponsesStream {

        @Test
        @DisplayName("基础文本流")
        void basicTextStream() {
            StreamTranslator t = toIR.createStreamToIR("gpt-4o");

            // role delta → created + output_item.added
            List<String> out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}");
            assertTrue(out.stream().anyMatch(s -> s.contains("response.created"))
                    || out.stream().anyMatch(s -> s.contains("response.output_item.added")));

            // content delta → output_text.delta
            out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"}}]}");
            assertTrue(out.stream().anyMatch(s -> s.contains("response.output_text.delta")));

            // [DONE] → completed
            out = t.feed("data: [DONE]");
            assertTrue(out.stream().anyMatch(s -> s.contains("response.completed")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("Chat stream created → Responses created_at")
        void streamCreatedTimestampToResponsesCreatedAt() {
            StreamTranslator t = toIR.createStreamToIR("gpt-4o");

            List<String> out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1710000222,\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"created_at\":1710000222")));
        }

        @Test
        @DisplayName("Chat stream 非法 created 不覆盖 Responses created_at")
        void invalidStreamCreatedIgnoredInResponsesCreatedAt() {
            StreamTranslator t = toIR.createStreamToIR("gpt-4o");

            List<String> out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":\"1710000222\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}");

            assertTrue(out.stream().anyMatch(s -> s.contains("response.created")));
            assertTrue(out.stream().noneMatch(s -> s.contains("\"created_at\":1710000222")));
        }

        @Test
        @DisplayName("Chat stream 非文本内容和参数不写入 Responses delta")
        void nonTextStreamPayloadsIgnoredInResponses() {
            StreamTranslator t = toIR.createStreamToIR("gpt-4o");

            List<String> out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":123,\"refusal\":{\"text\":\"No\"},\"reasoning_content\":true}}]}");

            assertTrue(out.stream().noneMatch(s -> s.contains("response.output_text.delta")));
            assertTrue(out.stream().noneMatch(s -> s.contains("response.refusal.delta")));
            assertTrue(out.stream().noneMatch(s -> s.contains("response.reasoning_summary_text.delta")));

            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}}]}}]}");
            out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":{\"city\":\"NYC\"}}}]}}]}");

            assertTrue(out.stream().noneMatch(s -> s.contains("response.function_call_arguments.delta")));
        }

        @Test
        @DisplayName("tool_calls 流")
        void toolCallsStream() {
            StreamTranslator t = toIR.createStreamToIR("gpt-4o");

            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}");

            // tool_calls delta → 应产生 function_call output_item
            List<String> out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}}]}}]}");
            assertFalse(out.isEmpty()); // 至少产出了事件

            // arguments delta
            out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"}}]}}]}");
            assertTrue(out.stream().anyMatch(s -> s.contains("response.function_call_arguments.delta")));

            out = t.feed("data: [DONE]");
            assertTrue(out.stream().anyMatch(s -> s.contains("response.function_call_arguments.done")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"")));
        }

        @Test
        @DisplayName("缺 id/name 的 Chat tool_call 流不进入 Responses IR")
        void invalidToolCallStreamShapesIgnored() {
            StreamTranslator t = toIR.createStreamToIR("gpt-4o");

            List<String> out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"\",\"type\":\"function\",\"function\":{\"name\":\"missing_id\"}}]}}]}");
            assertTrue(out.stream().noneMatch(s -> s.contains("\"type\":\"function_call\"")));

            out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_missing_name\",\"type\":\"function\",\"function\":{\"name\":\"\"}}]}}]}");
            assertTrue(out.stream().noneMatch(s -> s.contains("\"type\":\"function_call\"")));

            out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"}}]}}]}");
            assertTrue(out.stream().noneMatch(s -> s.contains("response.function_call_arguments.delta")));

            out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"function_call\":{\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"}}}]}");
            assertTrue(out.stream().noneMatch(s -> s.contains("\"type\":\"function_call\"")));
            assertTrue(out.stream().noneMatch(s -> s.contains("response.function_call_arguments.delta")));
        }

        @Test
        @DisplayName("多个 tool_calls 流按 index 独立累积参数")
        void multipleToolCallsStream() {
            StreamTranslator t = toIR.createStreamToIR("gpt-4o");

            List<String> out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}},{\"index\":1,\"id\":\"call_2\",\"type\":\"function\",\"function\":{\"name\":\"get_time\"}}]}}]}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"output_index\":0")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"output_index\":1")));

            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\":\"}},{\"index\":1,\"function\":{\"arguments\":\"{\\\"tz\\\":\"}}]}}]}");
            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,\"function\":{\"arguments\":\"\\\"UTC\\\"}\"}},{\"index\":0,\"function\":{\"arguments\":\"\\\"NYC\\\"}\"}}]}}]}");

            out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"call_id\":\"call_1\"")
                    && s.contains("\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"call_id\":\"call_2\"")
                    && s.contains("\"arguments\":\"{\\\"tz\\\":\\\"UTC\\\"}\"")));
        }

        @Test
        @DisplayName("旧式 function_call 流转换为 function_call item")
        void legacyFunctionCallStream() {
            StreamTranslator t = toIR.createStreamToIR("gpt-4o");

            List<String> out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"function_call\":{\"name\":\"get_weather\"}}}]}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"type\":\"function_call\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"name\":\"get_weather\"")));

            out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"function_call\":{\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"}}}]}");
            assertTrue(out.stream().anyMatch(s -> s.contains("response.function_call_arguments.delta")));

            out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"function_call\"}]}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"")));
        }

        @Test
        @DisplayName("finish_reason 后仍接收 usage-only chunk")
        void usageOnlyChunkAfterFinishReason() {
            StreamTranslator t = toIR.createStreamToIR("gpt-4o");

            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}");
            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"}}]}");

            List<String> out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":null}");
            assertFalse(t.isDone());
            assertTrue(out.stream().noneMatch(s -> s.contains("response.completed")));

            out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3,\"total_tokens\":14,\"prompt_tokens_details\":{\"cached_tokens\":7}}}");
            assertFalse(t.isDone());
            assertTrue(out.isEmpty());

            out = t.feed("data: [DONE]");
            assertTrue(t.isDone());
            assertTrue(out.stream().anyMatch(s -> s.contains("\"input_tokens\":11")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"output_tokens\":3")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"cached_tokens\":7")));
        }

        @Test
        @DisplayName("Chat stream 非法 usage token 不写入 Responses usage")
        void invalidChatStreamUsageTokensIgnored() {
            StreamTranslator t = toIR.createStreamToIR("gpt-4o");

            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}");
            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":null}");
            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":\"11\",\"completion_tokens\":-5,\"prompt_tokens_details\":{\"cached_tokens\":\"7\"},\"completion_tokens_details\":{\"reasoning_tokens\":{\"value\":4}}}}");
            List<String> out = t.feed("data: [DONE]");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"usage\":{\"input_tokens\":0,\"output_tokens\":0,\"total_tokens\":0}")));
            assertTrue(out.stream().noneMatch(s -> s.contains("input_tokens_details")));
            assertTrue(out.stream().noneMatch(s -> s.contains("output_tokens_details")));
        }

        @Test
        @DisplayName("stream usage completion_tokens_details.reasoning_tokens → output_tokens_details")
        void streamReasoningTokensUsage() {
            StreamTranslator t = toIR.createStreamToIR("gpt-4o");

            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}");
            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":null}");
            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":5,\"total_tokens\":16,\"completion_tokens_details\":{\"reasoning_tokens\":4}}}");
            List<String> out = t.feed("data: [DONE]");

            assertTrue(t.isDone());
            assertTrue(out.stream().anyMatch(s -> s.contains("\"output_tokens_details\":{\"reasoning_tokens\":4}")));
        }

        @Test
        @DisplayName("stream finish_reason=content_filter → incomplete content_filter")
        void streamContentFilterFinishReason() {
            StreamTranslator t = toIR.createStreamToIR("gpt-4o");

            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1710000000,\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}");
            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1710000000,\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"content_filter\"}]}");
            List<String> out = t.feed("data: [DONE]");

            assertTrue(t.isDone());
            assertTrue(out.stream().anyMatch(s -> s.contains("response.incomplete")));
            assertTrue(out.stream().noneMatch(s -> s.contains("response.completed")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"status\":\"incomplete\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"reason\":\"content_filter\"")));
        }

        @Test
        @DisplayName("Chat stream 非文本 finish_reason 不触发 Responses incomplete")
        void nonTextStreamFinishReasonIgnoredInResponsesStatus() {
            StreamTranslator t = toIR.createStreamToIR("gpt-4o");

            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}");
            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":{\"value\":\"content_filter\"}}]}");
            List<String> out = t.feed("data: [DONE]");

            assertTrue(out.stream().anyMatch(s -> s.contains("response.completed")));
            assertTrue(out.stream().noneMatch(s -> s.contains("response.incomplete")));
        }

        @Test
        @DisplayName("reasoning_content 流使用独立 reasoning item")
        void reasoningContentStreamUsesReasoningItem() {
            StreamTranslator t = toIR.createStreamToIR("gpt-4o");

            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}");
            List<String> out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"Thinking\"}}]}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"type\":\"reasoning\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("response.reasoning_summary_text.delta")));

            out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Answer\"}}]}");
            assertTrue(out.stream().anyMatch(s -> s.contains("response.reasoning_summary_text.done")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"type\":\"message\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"output_index\":1")));
        }

        @Test
        @DisplayName("refusal delta 流转换为 Responses refusal delta")
        void refusalDeltaStream() {
            StreamTranslator t = toIR.createStreamToIR("gpt-4o");

            t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}");
            List<String> out = t.feed("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"refusal\":\"No.\"}}]}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"type\":\"message\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("response.refusal.delta")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"delta\":\"No.\"")));
        }
    }

    // ========================
    // 流式 Responses IR SSE → Chat Completions SSE
    // ========================

    @Nested
    @DisplayName("流式 Responses IR SSE → Chat Completions SSE")
    class ResponsesToChatStream {

        @Test
        @DisplayName("text delta 流")
        void textDelta() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            // response.created → role chunk
            List<String> out = t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_stream\"}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"assistant\"")));

            // response.output_text.delta → content chunk
            out = t.feed("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"Hello\"")));
        }

        @Test
        @DisplayName("Responses stream created_at → Chat created")
        void streamCreatedAtToChatCreated() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_stream\",\"created_at\":1710000333}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"created\":1710000333")));
        }

        @Test
        @DisplayName("Responses stream 非法 model/created_at 不覆盖 Chat chunk 元数据")
        void invalidResponsesStreamMetadataIgnoredInChat() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_stream\",\"model\":{\"id\":\"bad\"},\"created_at\":\"1710000333\"}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"model\":\"gpt-4o\"")));
            assertTrue(out.stream().noneMatch(s -> s.contains("\"model\":\"\"")));
            assertTrue(out.stream().noneMatch(s -> s.contains("\"created\":1710000333")));
        }

        @Test
        @DisplayName("Responses stream 非文本内容和参数不降级到 Chat delta")
        void nonTextResponsesStreamPayloadsIgnoredInChat() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.output_text.delta\",\"delta\":123}");
            assertTrue(out.stream().noneMatch(s -> s.contains("\"content\"")));

            out = t.feed("data: {\"type\":\"response.refusal.delta\",\"delta\":{\"text\":\"No\"}}");
            assertTrue(out.stream().noneMatch(s -> s.contains("\"content\"")));

            out = t.feed("data: {\"type\":\"response.content_part.done\",\"part\":{\"type\":\"output_text\",\"text\":true}}");
            assertTrue(out.stream().noneMatch(s -> s.contains("\"content\"")));

            t.feed("data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"get_weather\"}}");
            out = t.feed("data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,\"delta\":{\"city\":\"NYC\"}}");
            assertTrue(out.stream().noneMatch(s -> s.contains("\"arguments\"")));
        }

        @Test
        @DisplayName("refusal delta 流作为文本 delta 输出")
        void refusalDelta() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.refusal.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"No.\"}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"content\":\"No.\"")));
        }

        @Test
        @DisplayName("content_part.done fallback 输出未见过的文本")
        void contentPartDoneFallback() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.content_part.done\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"Final\"}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"content\":\"Final\"")));
        }

        @Test
        @DisplayName("tool_call delta 流")
        void toolCallDelta() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            // response.output_item.added (function_call)
            List<String> out = t.feed("data: {\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"get_weather\"}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"tool_calls\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"call_1\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"get_weather\"")));

            // arguments delta (uses output_index to match)
            out = t.feed("data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,\"delta\":\"{\\\"city\\\":\"}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"arguments\"")));
        }

        @Test
        @DisplayName("output_item.done function_call fallback 输出完整 tool_call")
        void toolCallFromOutputItemDoneFallback() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\",\"status\":\"completed\"}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"tool_calls\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"call_1\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"get_weather\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"")));
        }

        @Test
        @DisplayName("output_item.done custom_tool_call fallback 输出完整 custom tool_call")
        void customToolCallFromOutputItemDoneFallback() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"custom_tool_call\",\"call_id\":\"call_custom_1\",\"name\":\"grammar\",\"input\":\"start: expr\",\"status\":\"completed\"}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"tool_calls\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"call_custom_1\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"type\":\"custom\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"custom\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"grammar\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"input\":\"start: expr\"")));
        }

        @Test
        @DisplayName("response.completed.output custom_tool_call fallback 输出完整 custom tool_call")
        void customToolCallFromCompletedOutputFallback() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"custom_tool_call\",\"call_id\":\"call_custom_1\",\"name\":\"grammar\",\"input\":\"start: expr\",\"status\":\"completed\"}]}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"tool_calls\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"type\":\"custom\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"input\":\"start: expr\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"finish_reason\":\"tool_calls\"")));
        }

        @Test
        @DisplayName("缺 call_id/name 的 Responses function_call 流不构造 Chat tool_call")
        void invalidFunctionCallStreamShapesIgnored() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"name\":\"missing_call_id\"}}");
            assertTrue(out.stream().noneMatch(s -> s.contains("\"tool_calls\"")));

            out = t.feed("data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,\"delta\":\"{\\\"city\\\":\\\"NYC\\\"}\"}");
            assertTrue(out.stream().noneMatch(s -> s.contains("\"tool_calls\"")));

            out = t.feed("data: {\"type\":\"response.output_item.done\",\"output_index\":1,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_missing_name\",\"arguments\":\"{}\",\"status\":\"completed\"}}");
            assertTrue(out.stream().noneMatch(s -> s.contains("\"tool_calls\"")));

            out = t.feed("data: {\"type\":\"response.output_item.added\",\"output_index\":2,\"item\":{\"type\":\"function_call\",\"call_id\":true,\"name\":\"boolean_call_id\"}}");
            assertTrue(out.stream().noneMatch(s -> s.contains("\"tool_calls\"")));

            out = t.feed("data: {\"type\":\"response.output_item.added\",\"output_index\":3,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_boolean_name\",\"name\":false}}");
            assertTrue(out.stream().noneMatch(s -> s.contains("\"tool_calls\"")));

            out = t.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"finish_reason\":\"stop\"")));
            assertTrue(out.stream().noneMatch(s -> s.contains("\"finish_reason\":\"tool_calls\"")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("response.completed.output fallback 输出最终文本和缓存 usage")
        void completedOutputFallbackAndCachedUsage() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"final only\"}]}],\"usage\":{\"input_tokens\":100,\"output_tokens\":5,\"input_tokens_details\":{\"cached_tokens\":80}}}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"content\":\"final only\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"cached_tokens\":80")));
            assertTrue(out.stream().anyMatch(s -> s.contains("data: [DONE]")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("多 tool_call 交错")
        void interleavedToolCalls() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            // 第一个 tool call (output_index=1)
            t.feed("data: {\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"get_weather\"}}");

            // 第二个 tool call (output_index=2)
            List<String> out = t.feed("data: {\"type\":\"response.output_item.added\",\"output_index\":2,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_2\",\"name\":\"get_time\"}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"index\":1")));

            // 第二个 tool call 的参数
            out = t.feed("data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":2,\"delta\":\"{\\\"tz\\\":\\\"UTC\\\"}\"}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"index\":1")));

            // 交错：第一个 tool call 的参数
            out = t.feed("data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,\"delta\":\"\\\"Tokyo\\\"}\"}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"index\":0")));
        }

        @Test
        @DisplayName("response.completed")
        void completed() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            // completed with usage
            List<String> out = t.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":50,\"output_tokens\":20,\"total_tokens\":70,\"input_tokens_details\":{\"cached_tokens\":30}}}}");
            // finish chunk + [DONE]
            assertTrue(out.stream().anyMatch(s -> s.contains("\"stop\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("[DONE]")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("Responses stream 非法 usage token 不降级到 Chat usage")
        void invalidResponsesStreamUsageTokensIgnored() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":\"13\",\"output_tokens\":-7,\"input_tokens_details\":{\"cached_tokens\":\"3\"},\"output_tokens_details\":{\"reasoning_tokens\":{\"value\":1}}}}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}")));
            assertTrue(out.stream().noneMatch(s -> s.contains("prompt_tokens_details")));
            assertTrue(out.stream().noneMatch(s -> s.contains("completion_tokens_details")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("response.done incomplete → length")
        void responseDoneIncomplete() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.done\",\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"usage\":{\"input_tokens\":13,\"output_tokens\":7}}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"length\"")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("response.incomplete without reason → length")
        void responseIncompleteWithoutReason() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\",\"usage\":{\"input_tokens\":13,\"output_tokens\":7}}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"finish_reason\":\"length\"")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("response.done incomplete content_filter → content_filter")
        void responseDoneIncompleteContentFilter() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.done\",\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"content_filter\"},\"usage\":{\"input_tokens\":13,\"output_tokens\":7}}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"finish_reason\":\"content_filter\"")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("Responses stream 非文本 status/reason 不降级为 Chat length")
        void nonTextResponsesStreamStatusIgnoredInChatFinishReason() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.done\",\"response\":{\"status\":{\"value\":\"incomplete\"},\"incomplete_details\":{\"reason\":{\"value\":\"max_output_tokens\"}},\"usage\":{\"input_tokens\":13,\"output_tokens\":7}}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"finish_reason\":\"stop\"")));
            assertTrue(out.stream().noneMatch(s -> s.contains("\"finish_reason\":\"length\"")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("Responses stream 非文本 type 不触发 Chat 终止")
        void nonTextResponsesStreamTypeIgnoredInChat() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":{\"value\":\"response.done\"},\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":13,\"output_tokens\":7}}}");

            assertTrue(out.isEmpty());
            assertFalse(t.isDone());
        }

        @Test
        @DisplayName("response.done output_tokens_details.reasoning_tokens → completion_tokens_details")
        void responseDoneReasoningTokensUsage() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.done\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":13,\"output_tokens\":7,\"output_tokens_details\":{\"reasoning_tokens\":3}}}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"completion_tokens_details\":{\"reasoning_tokens\":3}")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("tool_calls → finish_reason=tool_calls")
        void completedWithToolCalls() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            // function_call item
            t.feed("data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"get_weather\"}}");

            // completed with tool calls
            List<String> out = t.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"tool_calls\"")));
        }

        @Test
        @DisplayName("Responses hosted tool call 流式事件不降级为 Chat 文本或 tool_calls")
        void hostedToolCallStreamEventsAreIgnored() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");
            t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_hosted\",\"model\":\"gpt-4o\"}}");

            String[] hostedTypes = {
                    "file_search_call",
                    "computer_call",
                    "code_interpreter_call",
                    "mcp_call",
                    "image_generation_call",
                    "local_shell_call"
            };

            for (int i = 0; i < hostedTypes.length; i++) {
                List<String> added = t.feed("data: {\"type\":\"response.output_item.added\",\"output_index\":" + i
                        + ",\"item\":{\"type\":\"" + hostedTypes[i] + "\",\"id\":\"hosted_" + i + "\",\"status\":\"in_progress\"}}");
                assertTrue(added.stream().noneMatch(s -> s.contains("\"tool_calls\"")));
                assertTrue(added.stream().noneMatch(s -> s.contains("\"content\"")));

                List<String> done = t.feed("data: {\"type\":\"response.output_item.done\",\"output_index\":" + i
                        + ",\"item\":{\"type\":\"" + hostedTypes[i] + "\",\"id\":\"hosted_" + i + "\",\"status\":\"completed\"}}");
                assertTrue(done.stream().noneMatch(s -> s.contains("\"tool_calls\"")));
                assertTrue(done.stream().noneMatch(s -> s.contains("\"content\"")));
            }

            List<String> out = t.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"file_search_call\",\"id\":\"fs_1\",\"status\":\"completed\"},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"visible\"}]}]}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"content\":\"visible\"")));
            assertTrue(out.stream().noneMatch(s -> s.contains("\"tool_calls\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"finish_reason\":\"stop\"")));
        }

        @Test
        @DisplayName("Responses stream 非法 output_index 不按字符串数字绑定 Chat tool_call")
        void invalidResponsesStreamOutputIndexNotCoercedInChat() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.output_item.added\",\"output_index\":\"7\",\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"get_weather\"}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"tool_calls\"")));

            out = t.feed("data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":7,\"delta\":\"{\\\"city\\\":\\\"NYC\\\"}\"}");
            assertTrue(out.stream().noneMatch(s -> s.contains("\"arguments\"")));
        }

        @Test
        @DisplayName("response.failed 流式降级为 Chat 终止 chunk")
        void responseFailedStreamTerminatesChatWithoutErrorContent() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"Internal error\"},\"usage\":{\"input_tokens\":12,\"output_tokens\":0}}}");

            assertTrue(t.isDone());
            assertEquals(12, t.getInputTokens());
            assertEquals(0, t.getOutputTokens());
            assertTrue(out.stream().anyMatch(s -> s.contains("\"finish_reason\":\"stop\"")));
            assertTrue(out.stream().anyMatch("data: [DONE]"::equals));
            assertTrue(out.stream().noneMatch(s -> s.contains("Internal error")));
        }

        @Test
        @DisplayName("reasoning delta 流")
        void reasoningDelta() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"Thinking...\"}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"reasoning_content\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"Thinking...\"")));
        }

        @Test
        @DisplayName("reasoning_text delta 流")
        void reasoningTextDelta() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"Full thinking...\"}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"reasoning_content\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"Full thinking...\"")));
        }

        @Test
        @DisplayName("完整流式往返")
        void streamRoundTrip() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            // 1. response.created
            t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_rt\"}}");

            // 2. text deltas
            for (String text : new String[]{"Hello", ", ", "world", "!"}) {
                t.feed("data: {\"type\":\"response.output_text.delta\",\"delta\":\"" + text + "\"}");
            }

            // 3. response.completed
            List<String> out = t.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":4,\"total_tokens\":14}}}");

            // 验证最终完成
            assertTrue(out.stream().anyMatch(s -> s.contains("[DONE]")));
            assertTrue(t.isDone());
        }
    }
}
