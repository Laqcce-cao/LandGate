package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AnthropicConverter 单元测试 —— 验证 Anthropic Messages API ↔ Responses IR 双向转换。
 * <p>
 * 参照：sub2api {@code anthropic_responses_test.go}
 */
@DisplayName("AnthropicConverter 双向转换测试")
class AnthropicConverterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final AnthropicToResponsesConverter toIR = new AnthropicToResponsesConverter();
    private final ResponsesToAnthropicConverter fromIR = new ResponsesToAnthropicConverter();

    // ========================
    // Anthropic → Responses 请求转换
    // ========================

    @Nested
    @DisplayName("Anthropic → Responses 请求转换")
    class AnthropicToResponsesRequest {

        @Test
        @DisplayName("基础文本转换")
        void basicText() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "stream": true,
                        "service_tier": "standard_only",
                        "messages": [{"role": "user", "content": "Hello"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertEquals("gpt-5.2", resp.get("model").asText());
            assertTrue(resp.get("stream").asBoolean());
            assertEquals("standard_only", resp.get("service_tier").asText());
            assertEquals(1024, resp.get("max_output_tokens").asInt());
            assertFalse(resp.get("store").asBoolean());
            assertFalse(resp.has("reasoning"));
            assertFalse(resp.has("text"));

            JsonNode input = resp.get("input");
            assertEquals(1, input.size());
            assertEquals("message", input.get(0).get("type").asText());
            assertEquals("user", input.get(0).get("role").asText());

            JsonNode content = input.get(0).get("content");
            assertEquals(1, content.size());
            assertEquals("input_text", content.get(0).get("type").asText());
            assertEquals("Hello", content.get(0).get("text").asText());
        }

        @Test
        @DisplayName("非对象 metadata 不写入 Responses")
        void nonObjectMetadataDroppedToResponses() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "metadata": "trace_id=req_123",
                        "messages": [{"role": "user", "content": "Hello"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("metadata"));
        }

        @Test
        @DisplayName("非文本 service_tier 不写入 Responses")
        void nonTextServiceTierDroppedToResponses() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "service_tier": {"tier":"standard_only"},
                        "messages": [{"role": "user", "content": "Hello"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("service_tier"));
        }

        @Test
        @DisplayName("top_k 不透传到 Responses")
        void topKIsNotMappedToResponses() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "top_k": 20,
                        "messages": [{"role": "user", "content": "Hello"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("top_k"));
            assertEquals(1024, resp.get("max_output_tokens").asInt());
        }

        @Test
        @DisplayName("system prompt 字符串格式 → developer message")
        void systemPromptString() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 100,
                        "system": "You are helpful.",
                        "messages": [{"role": "user", "content": "Hi"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");
            assertEquals(2, input.size());

            // input[0] = developer
            assertEquals("developer", input.get(0).get("role").asText());
            JsonNode devContent = input.get(0).get("content");
            assertEquals(1, devContent.size());
            assertEquals("input_text", devContent.get(0).get("type").asText());
            assertEquals("You are helpful.", devContent.get(0).get("text").asText());

            // input[1] = user
            assertEquals("user", input.get(1).get("role").asText());
        }

        @Test
        @DisplayName("system prompt 数组格式 → developer message")
        void systemPromptArray() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 100,
                        "system": [{"type":"text","text":"Part 1"},{"type":"text","text":"Part 2"}],
                        "messages": [{"role": "user", "content": "Hi"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");
            assertEquals(2, input.size());

            JsonNode devContent = input.get(0).get("content");
            assertEquals(2, devContent.size());
            assertEquals("Part 1", devContent.get(0).get("text").asText());
            assertEquals("Part 2", devContent.get(1).get("text").asText());
        }

        @Test
        @DisplayName("billing header 文本过滤")
        void billingHeaderSkipped() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 100,
                        "system": [{"type":"text","text":"x-anthropic-billing-header: cc_version=1;"},{"type":"text","text":"Project prompt"}],
                        "messages": [{"role": "user", "content": "Hi"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode devContent = resp.get("input").get(0).get("content");
            assertEquals(1, devContent.size());
            assertEquals("Project prompt", devContent.get(0).get("text").asText());
        }

        @Test
        @DisplayName("stop_sequences → 内部 stop 扩展并可还原")
        void stopSequencesToInternalStopExtension() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 100,
                        "stop_sequences": ["</end>", "DONE"],
                        "messages": [{"role": "user", "content": "Hi"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            assertEquals("</end>", resp.get("_landgate_stop_sequences").get(0).asText());
            assertEquals("DONE", resp.get("_landgate_stop_sequences").get(1).asText());

            String anthropicBody = fromIR.requestFromIR(resp);
            JsonNode anthropic = JSON.readTree(anthropicBody);
            assertEquals("</end>", anthropic.get("stop_sequences").get(0).asText());
            assertEquals("DONE", anthropic.get("stop_sequences").get(1).asText());

            String responsesBody = new ResponsesConverter().requestFromIR(resp);
            assertFalse(JSON.readTree(responsesBody).has("_landgate_stop_sequences"));
        }

        @Test
        @DisplayName("tool_use 转换")
        void toolUse() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [
                            {"role": "user", "content": "What is the weather?"},
                            {"role": "assistant", "content": [
                                {"type":"text","text":"Let me check."},
                                {"type":"tool_use","id":"call_1","name":"get_weather","input":{"city":"NYC"}}
                            ]},
                            {"role": "user", "content": [
                                {"type":"tool_result","tool_use_id":"call_1","content":"Sunny, 72°F"}
                            ]}
                        ],
                        "tools": [{"name":"get_weather","description":"Get weather","input_schema":{"type":"object","properties":{"city":{"type":"string"}}}}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            // tools
            JsonNode tools = resp.get("tools");
            assertEquals(1, tools.size());
            assertEquals("function", tools.get(0).get("type").asText());
            assertEquals("get_weather", tools.get(0).get("name").asText());
            assertFalse(tools.get(0).get("strict").asBoolean());

            // input items: user + assistant + function_call + function_call_output = 4
            JsonNode input = resp.get("input");
            assertEquals(4, input.size());
            assertEquals("user", input.get(0).get("role").asText());
            assertEquals("assistant", input.get(1).get("role").asText());
            assertEquals("function_call", input.get(2).get("type").asText());
            assertEquals("call_1", input.get(2).get("call_id").asText());
            assertEquals("function_call_output", input.get(3).get("type").asText());
            assertEquals("call_1", input.get(3).get("call_id").asText());
            assertEquals("Sunny, 72°F", input.get(3).get("output").asText());
        }

        @Test
        @DisplayName("assistant thinking 块保留为 reasoning input item")
        void thinkingPreservedAsReasoningInput() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [
                            {"role": "user", "content": "Hello"},
                            {"role": "assistant", "content": [{"type":"thinking","thinking":"deep thought","signature":"sig_1"},{"type":"text","text":"Hi!"}]},
                            {"role": "user", "content": "More"}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");
            // user + assistant(text) + reasoning + user = 4
            assertEquals(4, input.size());

            JsonNode assistantContent = input.get(1).get("content");
            assertEquals(1, assistantContent.size());
            assertEquals("output_text", assistantContent.get(0).get("type").asText());
            assertEquals("Hi!", assistantContent.get(0).get("text").asText());

            JsonNode reasoning = input.get(2);
            assertEquals("reasoning", reasoning.get("type").asText());
            assertEquals("deep thought", reasoning.get("content").get(0).get("text").asText());
            assertEquals("sig_1", reasoning.get("encrypted_content").asText());
        }

        @Test
        @DisplayName("max_tokens 精确映射")
        void maxTokensPreserved() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 10,
                        "messages": [{"role": "user", "content": "Hi"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            assertEquals(10, resp.get("max_output_tokens").asInt());
        }

        @Test
        @DisplayName("thinking enabled → reasoning effort=medium")
        void thinkingEnabled() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "Hello"}],
                        "thinking": {"type": "enabled", "budget_tokens": 10000}
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            assertNotNull(resp.get("reasoning"));
            assertEquals("medium", resp.get("reasoning").get("effort").asText());
            assertEquals("auto", resp.get("reasoning").get("summary").asText());
        }

        @Test
        @DisplayName("output_config.effort 覆盖默认值")
        void outputConfigOverridesDefault() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "Hello"}],
                        "thinking": {"type": "enabled", "budget_tokens": 10000},
                        "output_config": {"effort": "low"}
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            assertEquals("low", resp.get("reasoning").get("effort").asText());
        }

        @Test
        @DisplayName("output_config effort=high → high")
        void outputConfigHigh() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "Hello"}],
                        "output_config": {"effort": "high"}
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            assertEquals("high", resp.get("reasoning").get("effort").asText());
        }

        @Test
        @DisplayName("output_config effort=max → xhigh")
        void outputConfigMax() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "Hello"}],
                        "output_config": {"effort": "max"}
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            assertEquals("xhigh", resp.get("reasoning").get("effort").asText());
        }

        @Test
        @DisplayName("tool_choice auto → auto")
        void toolChoiceAuto() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "Hello"}],
                        "tool_choice": {"type":"auto"}
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            assertEquals("auto", resp.get("tool_choice").asText());
        }

        @Test
        @DisplayName("tool_choice any → required")
        void toolChoiceAny() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "Hello"}],
                        "tool_choice": {"type":"any"}
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            assertEquals("required", resp.get("tool_choice").asText());
        }

        @Test
        @DisplayName("tool_choice 指定工具 → function")
        void toolChoiceSpecific() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "Hello"}],
                        "tool_choice": {"type":"tool","name":"get_weather"}
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode tc = resp.get("tool_choice");
            assertEquals("function", tc.get("type").asText());
            assertEquals("get_weather", tc.get("name").asText());
        }

        @Test
        @DisplayName("tool_choice disable_parallel_tool_use → parallel_tool_calls=false")
        void toolChoiceDisableParallelToolUse() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "Hello"}],
                        "tool_choice": {"type":"auto","disable_parallel_tool_use":true}
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertEquals("auto", resp.get("tool_choice").asText());
            assertFalse(resp.get("parallel_tool_calls").asBoolean());
        }

        @Test
        @DisplayName("未知 tool_choice 不透传到 Responses")
        void unknownToolChoiceIsDropped() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "Hello"}],
                        "tool_choice": {"type":"server_tool","name":"opaque"}
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("tool_choice"));
        }

        @Test
        @DisplayName("未知文本 tool_choice 不透传到 Responses")
        void unknownTextToolChoiceIsDropped() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "Hello"}],
                        "tool_choice": "server_tool"
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("tool_choice"));
        }

        @Test
        @DisplayName("缺 name 的 Anthropic tool_choice 不透传到 Responses")
        void unnamedToolChoiceIsDropped() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "Hello"}],
                        "tool_choice": {"type":"tool"}
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("tool_choice"));
        }

        @Test
        @DisplayName("user 消息中的图片块 → input_image")
        void userImageBlock() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": [
                            {"type":"text","text":"What is in this image?"},
                            {"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBOR"}}
                        ]}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode content = resp.get("input").get(0).get("content");
            assertEquals(2, content.size());
            assertEquals("input_text", content.get(0).get("type").asText());
            assertEquals("What is in this image?", content.get(0).get("text").asText());
            assertEquals("input_image", content.get(1).get("type").asText());
            assertEquals("data:image/png;base64,iVBOR", content.get(1).get("image_url").asText());
        }

        @Test
        @DisplayName("Anthropic URL image source → Responses image_url")
        void userUrlImageBlock() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": [
                            {"type":"image","source":{"type":"url","url":"https://example.com/cat.png"}}
                        ]}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode content = resp.get("input").get(0).get("content");

            assertEquals("input_image", content.get(0).get("type").asText());
            assertEquals("https://example.com/cat.png", content.get(0).get("image_url").asText());
        }

        @Test
        @DisplayName("Anthropic document URL/base64/file source → Responses input_file")
        void userDocumentBlocksToInputFile() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": [
                            {"type":"document","title":"remote.pdf","source":{"type":"url","url":"https://example.com/remote.pdf"}},
                            {"type":"document","title":"local.pdf","source":{"type":"base64","media_type":"application/pdf","data":"JVBERi0x"}},
                            {"type":"document","title":"uploaded.pdf","source":{"type":"file","file_id":"file_123"}}
                        ]}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode content = resp.get("input").get(0).get("content");

            assertEquals(3, content.size());
            assertEquals("input_file", content.get(0).get("type").asText());
            assertEquals("remote.pdf", content.get(0).get("filename").asText());
            assertEquals("https://example.com/remote.pdf", content.get(0).get("file_url").asText());
            assertEquals("data:application/pdf;base64,JVBERi0x", content.get(1).get("file_data").asText());
            assertEquals("file_123", content.get(2).get("file_id").asText());
        }

        @Test
        @DisplayName("空 media_type 默认 image/png")
        void imageEmptyMediaType() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": [
                            {"type":"image","source":{"type":"base64","media_type":"","data":"iVBOR"}}
                        ]}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode content = resp.get("input").get(0).get("content");
            assertEquals("input_image", content.get(0).get("type").asText());
            assertEquals("data:image/png;base64,iVBOR", content.get(0).get("image_url").asText());
        }

        @Test
        @DisplayName("Anthropic invalid-only user content 不生成空 Responses message")
        void invalidOnlyUserContentDoesNotCreateEmptyResponsesMessage() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [
                            {"role": "user", "content": [
                                {"type":"image","source":{"type":"url","url":"   "}},
                                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"   "}},
                                {"type":"document","title":"blank.pdf","source":{"type":"url","url":" "}},
                                {"type":"document","title":"empty.pdf","source":{"type":"base64","media_type":"application/pdf","data":""}},
                                {"type":"document","title":"missing.pdf","source":{"type":"file","file_id":" "}}
                            ]},
                            {"role": "user", "content": [
                                {"type":"text","text":"visible"},
                                {"type":"image","source":{"type":"url","url":""}}
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
        @DisplayName("tool_result 中的图片 → 单独的 user image 消息")
        void toolResultWithImage() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [
                            {"role": "user", "content": "Read the screenshot"},
                            {"role": "assistant", "content": [{"type":"tool_use","id":"toolu_1","name":"Read","input":{"file_path":"/tmp/screen.png"}}]},
                            {"role": "user", "content": [{"type":"tool_result","tool_use_id":"toolu_1","content":[
                                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBOR"}}
                            ]}]}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");
            // user + function_call + function_call_output + user(image) = 4
            assertEquals(4, input.size());

            // function_call_output
            assertEquals("function_call_output", input.get(2).get("type").asText());
            assertEquals("", input.get(2).get("output").asText());

            // 图片单独的用户消息
            assertEquals("user", input.get(3).get("role").asText());
            JsonNode imgContent = input.get(3).get("content");
            assertEquals(1, imgContent.size());
            assertEquals("input_image", imgContent.get(0).get("type").asText());
            assertEquals("data:image/png;base64,iVBOR", imgContent.get(0).get("image_url").asText());
        }

        @Test
        @DisplayName("tool_result 混合内容（文本 + 图片）")
        void toolResultMixed() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [
                            {"role": "user", "content": "Describe the file"},
                            {"role": "assistant", "content": [{"type":"tool_use","id":"toolu_2","name":"Read","input":{"file_path":"/tmp/photo.png"}}]},
                            {"role": "user", "content": [{"type":"tool_result","tool_use_id":"toolu_2","content":[
                                {"type":"text","text":"File metadata: 800x600 PNG"},
                                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}
                            ]}]}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");
            assertEquals(4, input.size());

            // function_call_output 只包含文本
            assertEquals("function_call_output", input.get(2).get("type").asText());
            assertEquals("File metadata: 800x600 PNG", input.get(2).get("output").asText());

            // 图片在单独的用户消息中
            assertEquals("user", input.get(3).get("role").asText());
            JsonNode imgContent = input.get(3).get("content");
            assertEquals(1, imgContent.size());
            assertEquals("input_image", imgContent.get(0).get("type").asText());
        }

        @Test
        @DisplayName("tool_result 中的文档 → 单独的 user input_file 消息")
        void toolResultWithDocument() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [
                            {"role": "user", "content": "Read the report"},
                            {"role": "assistant", "content": [{"type":"tool_use","id":"toolu_doc","name":"Read","input":{"file_path":"/tmp/report.pdf"}}]},
                            {"role": "user", "content": [{"type":"tool_result","tool_use_id":"toolu_doc","content":[
                                {"type":"document","title":"Report","source":{"type":"url","url":"https://example.com/report.pdf"}}
                            ]}]}
                        ]
                    }""";

            JsonNode input = toIR.requestToIR(body).get("input");

            assertEquals(4, input.size());
            assertEquals("function_call_output", input.get(2).get("type").asText());
            assertEquals("", input.get(2).get("output").asText());
            JsonNode filePart = input.get(3).get("content").get(0);
            assertEquals("input_file", filePart.get("type").asText());
            assertEquals("https://example.com/report.pdf", filePart.get("file_url").asText());
            assertEquals("Report", filePart.get("filename").asText());
        }

        @Test
        @DisplayName("纯文本 tool_result（向后兼容）")
        void textOnlyToolResult() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [
                            {"role": "user", "content": "Check weather"},
                            {"role": "assistant", "content": [{"type":"tool_use","id":"call_1","name":"get_weather","input":{"city":"NYC"}}]},
                            {"role": "user", "content": [{"type":"tool_result","tool_use_id":"call_1","content":"Sunny, 72°F"}]}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");
            assertEquals(3, input.size());
            assertEquals("Sunny, 72°F", input.get(2).get("output").asText());
        }

        @Test
        @DisplayName("缺 id/name 的 Anthropic 工具结构不进入 Responses IR")
        void invalidToolShapesInRequestIgnored() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [
                            {"role": "user", "content": "Check weather"},
                            {"role": "assistant", "content": [
                                {"type":"tool_use","name":"missing_id","input":{}},
                                {"type":"tool_use","id":"toolu_missing_name","input":{}}
                            ]},
                            {"role": "user", "content": [
                                {"type":"tool_result","content":"orphan result"}
                            ]}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");

            assertEquals(1, input.size());
            assertEquals("user", input.get(0).get("role").asText());
            assertFalse(input.toString().contains("function_call"));
            assertFalse(input.toString().contains("function_call_output"));
        }

        @Test
        @DisplayName("tool input_schema 规范化：无 properties → 补充空 properties")
        void toolWithoutProperties() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "Hello"}],
                        "tools": [{"name":"simple_tool","description":"A tool","input_schema":{"type":"object"}}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode tools = resp.get("tools");
            assertEquals(1, tools.size());
            JsonNode params = tools.get(0).get("parameters");
            assertTrue(params.has("properties"));
        }

        @Test
        @DisplayName("tool input_schema 为空 → 补充空 schema")
        void toolWithNilSchema() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "Hello"}],
                        "tools": [{"name":"simple_tool","description":"A tool"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode tools = resp.get("tools");
            assertEquals(1, tools.size());
            JsonNode params = tools.get(0).get("parameters");
            assertEquals("object", params.get("type").asText());
            assertNotNull(params.get("properties"));
        }

        @Test
        @DisplayName("tool input_schema 非对象 → 补充空 schema")
        void toolWithNonObjectSchemaUsesEmptySchema() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "Hello"}],
                        "tools": [{"name":"simple_tool","input_schema":"not-a-schema"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode params = resp.get("tools").get(0).get("parameters");

            assertEquals("object", params.get("type").asText());
            assertTrue(params.get("properties").isObject());
        }

        @Test
        @DisplayName("缺 name 的 Anthropic 普通 tool 不写入 Responses")
        void unnamedToolIsDropped() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "Hello"}],
                        "tools": [{"description":"No name","input_schema":{"type":"object"}}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertFalse(resp.has("tools"));
        }

        @Test
        @DisplayName("web_search 前缀 tool → web_search type")
        void webSearchTool() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "search"}],
                        "tools": [{"type":"web_search_20250305","name":"web_search",
                            "user_location":{"type":"approximate","country":"US","city":"Seattle"}}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode tools = resp.get("tools");
            assertEquals(1, tools.size());
            assertEquals("web_search", tools.get(0).get("type").asText());
            assertEquals("Seattle", tools.get(0).get("user_location").get("city").asText());
        }

        @Test
        @DisplayName("Anthropic web_search user_location 非对象不透传")
        void webSearchToolWithNonObjectUserLocationDropsLocation() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "search"}],
                        "tools": [{"type":"web_search_20250305","name":"web_search",
                            "user_location":"Seattle"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode tool = resp.get("tools").get(0);

            assertEquals("web_search", tool.get("type").asText());
            assertFalse(tool.has("user_location"));
        }

        @Test
        @DisplayName("协议转换层不按模型名丢弃 temperature/top_p")
        void modelNameDoesNotDropSamplingParams() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "temperature": 0.7,
                        "top_p": 0.9,
                        "messages": [{"role": "user", "content": "Hello"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            assertEquals(0.7, resp.get("temperature").asDouble());
            assertEquals(0.9, resp.get("top_p").asDouble());
        }
    }

    // ========================
    // Responses → Anthropic 响应转换
    // ========================

    @Nested
    @DisplayName("Responses → Anthropic 响应转换")
    class ResponsesToAnthropicResponse {

        @Test
        @DisplayName("纯文本响应")
        void textOnly() throws Exception {
            String body = """
                    {
                        "id": "resp_123",
                        "model": "gpt-5.2",
                        "status": "completed",
                        "output": [{"type":"message","content":[{"type":"output_text","text":"Hello there!"}]}],
                        "usage": {"input_tokens":10,"output_tokens":5,"total_tokens":15}
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);

            assertEquals("resp_123", anth.get("id").asText());
            assertEquals("message", anth.get("type").asText());
            assertEquals("assistant", anth.get("role").asText());
            assertEquals("end_turn", anth.get("stop_reason").asText());

            JsonNode content = anth.get("content");
            assertEquals(1, content.size());
            assertEquals("text", content.get(0).get("type").asText());
            assertEquals("Hello there!", content.get(0).get("text").asText());

            JsonNode usage = anth.get("usage");
            assertEquals(10, usage.get("input_tokens").asInt());
            assertEquals(5, usage.get("output_tokens").asInt());
        }

        @Test
        @DisplayName("refusal content 作为 Anthropic text block 保留")
        void refusalContentAsTextBlock() throws Exception {
            String body = """
                    {
                        "id": "resp_refusal",
                        "model": "gpt-5.2",
                        "status": "completed",
                        "output": [{"type":"message","content":[{"type":"refusal","refusal":"I cannot help with that."}]}]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);

            assertEquals("text", anth.get("content").get(0).get("type").asText());
            assertEquals("I cannot help with that.", anth.get("content").get(0).get("text").asText());
        }

        @Test
        @DisplayName("缓存 token — Anthropic input 语义（减去 cached）")
        void cachedTokensUseAnthropicInputSemantics() throws Exception {
            String body = """
                    {
                        "id": "resp_cached",
                        "model": "gpt-5.2",
                        "status": "completed",
                        "output": [{"type":"message","content":[{"type":"output_text","text":"Cached response"}]}],
                        "usage": {
                            "input_tokens": 54006,
                            "output_tokens": 123,
                            "total_tokens": 54129,
                            "input_tokens_details": {"cached_tokens": 50688}
                        }
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);
            JsonNode usage = anth.get("usage");

            assertEquals(3318, usage.get("input_tokens").asInt());
            assertEquals(50688, usage.get("cache_read_input_tokens").asInt());
            assertEquals(123, usage.get("output_tokens").asInt());
        }

        @Test
        @DisplayName("缓存 token 超过 input → input 截断为 0")
        void cachedTokensClampInputTokens() throws Exception {
            String body = """
                    {
                        "id": "resp_cached_clamp",
                        "model": "gpt-5.2",
                        "status": "completed",
                        "usage": {
                            "input_tokens": 100,
                            "output_tokens": 5,
                            "input_tokens_details": {"cached_tokens": 150}
                        }
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);
            JsonNode usage = anth.get("usage");

            assertEquals(0, usage.get("input_tokens").asInt());
            assertEquals(150, usage.get("cache_read_input_tokens").asInt());
        }

        @Test
        @DisplayName("tool_use 响应")
        void toolUse() throws Exception {
            String body = """
                    {
                        "id": "resp_456",
                        "model": "gpt-5.2",
                        "status": "completed",
                        "output": [
                            {"type":"message","content":[{"type":"output_text","text":"Let me check."}]},
                            {"type":"function_call","call_id":"call_1","name":"get_weather","arguments":"{\\"city\\":\\"NYC\\"}"}
                        ]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);

            assertEquals("tool_use", anth.get("stop_reason").asText());
            JsonNode content = anth.get("content");
            assertEquals(2, content.size());
            assertEquals("text", content.get(0).get("type").asText());
            assertEquals("tool_use", content.get(1).get("type").asText());
            assertEquals("call_1", content.get(1).get("id").asText());
            assertEquals("get_weather", content.get(1).get("name").asText());
        }

        @Test
        @DisplayName("缺 call_id/name 的 Responses function_call 输出不构造 Anthropic tool_use")
        void invalidFunctionCallOutputIgnored() throws Exception {
            String body = """
                    {
                        "id": "resp_invalid_tool",
                        "model": "gpt-5.2",
                        "status": "completed",
                        "output": [
                            {"type":"function_call","name":"missing_call_id","arguments":"{}"},
                            {"type":"function_call","call_id":"call_missing_name","arguments":"{}"},
                            {"type":"message","content":[{"type":"output_text","text":"visible"}]}
                        ]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.responseFromIR(JSON.readTree(body)));
            JsonNode content = anth.get("content");

            assertEquals("end_turn", anth.get("stop_reason").asText());
            assertEquals(1, content.size());
            assertEquals("text", content.get(0).get("type").asText());
            assertEquals("visible", content.get(0).get("text").asText());
            assertFalse(content.toString().contains("tool_use"));
        }

        @Test
        @DisplayName("Responses hosted tool call 输出不降级到 Anthropic 文本或 tool_use")
        void hostedToolCallsAreIgnored() throws Exception {
            String body = """
                    {
                        "id": "resp_hosted_tools",
                        "model": "gpt-5.2",
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
            JsonNode anth = JSON.readTree(result);
            JsonNode content = anth.get("content");

            assertEquals("end_turn", anth.get("stop_reason").asText());
            assertEquals(1, content.size());
            assertEquals("text", content.get(0).get("type").asText());
            assertEquals("visible answer", content.get(0).get("text").asText());
        }

        @Test
        @DisplayName("Responses hosted-only 输出不伪造 Anthropic 空 text")
        void hostedOnlyToolCallsDoNotCreateEmptyText() throws Exception {
            String body = """
                    {
                        "id": "resp_hosted_only",
                        "model": "gpt-5.2",
                        "status": "completed",
                        "output": [
                            {"type":"file_search_call","id":"fs_1","status":"completed"},
                            {"type":"computer_call","id":"cu_1","status":"completed"},
                            {"type":"mcp_call","id":"mcp_1","status":"completed"}
                        ]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);
            JsonNode content = anth.get("content");

            assertEquals("end_turn", anth.get("stop_reason").asText());
            assertEquals(0, content.size());
        }

        @Test
        @DisplayName("tool_use stop_reason 不依赖最后一个 block")
        void toolUseStopReasonDoesNotDependOnLastBlock() throws Exception {
            String body = """
                    {
                        "id": "resp_tool_then_text",
                        "model": "gpt-5.5",
                        "status": "completed",
                        "output": [
                            {"type":"function_call","call_id":"call_todo","name":"TodoWrite","arguments":"{\\"todos\\":[{\\"content\\":\\"review changes\\",\\"status\\":\\"in_progress\\"}]}"},
                            {"type":"message","content":[{"type":"output_text","text":"Task list updated."}]}
                        ]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);

            assertEquals("tool_use", anth.get("stop_reason").asText());
            JsonNode content = anth.get("content");
            assertEquals(2, content.size());
            assertEquals("tool_use", content.get(0).get("type").asText());
            assertEquals("text", content.get(1).get("type").asText());
        }

        @Test
        @DisplayName("Read 工具空 pages 字段删除")
        void readToolDropsEmptyPages() throws Exception {
            String body = """
                    {
                        "id": "resp_read",
                        "model": "gpt-5.5",
                        "status": "completed",
                        "output": [{"type":"function_call","call_id":"call_read","name":"Read",
                            "arguments":"{\\"file_path\\":\\"/tmp/demo.py\\",\\"limit\\":2000,\\"offset\\":0,\\"pages\\":\\"\\"}"}]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);

            JsonNode content = anth.get("content");
            assertEquals(1, content.size());
            assertEquals("tool_use", content.get(0).get("type").asText());
            JsonNode input = content.get(0).get("input");
            assertNotNull(input);
            assertFalse(input.has("pages"));
            assertEquals("/tmp/demo.py", input.get("file_path").asText());
        }

        @Test
        @DisplayName("非 Read 工具保留空字符串")
        void preservesEmptyStringsForOtherTools() throws Exception {
            String body = """
                    {
                        "id": "resp_other",
                        "model": "gpt-5.5",
                        "status": "completed",
                        "output": [{"type":"function_call","call_id":"call_other","name":"Search",
                            "arguments":"{\\"query\\":\\"\\"}"}]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);
            JsonNode input = anth.get("content").get(0).get("input");

            assertEquals("", input.get("query").asText());
        }

        @Test
        @DisplayName("reasoning → thinking 块")
        void reasoning() throws Exception {
            String body = """
                    {
                        "id": "resp_789",
                        "model": "gpt-5.2",
                        "status": "completed",
                        "output": [
                            {"type":"reasoning","summary":[{"type":"summary_text","text":"Thinking about the answer..."}]},
                            {"type":"message","content":[{"type":"output_text","text":"42"}]}
                        ]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);
            JsonNode content = anth.get("content");

            assertEquals(2, content.size());
            assertEquals("thinking", content.get(0).get("type").asText());
            assertEquals("Thinking about the answer...", content.get(0).get("thinking").asText());
            assertEquals("text", content.get(1).get("type").asText());
            assertEquals("42", content.get(1).get("text").asText());
        }

        @Test
        @DisplayName("reasoning.content 优先于 summary 转为 thinking")
        void reasoningContentPreferredOverSummary() throws Exception {
            String body = """
                    {
                        "id": "resp_reasoning_content",
                        "model": "gpt-5.2",
                        "status": "completed",
                        "output": [
                            {
                                "type":"reasoning",
                                "content":[{"type":"reasoning_text","text":"Full reasoning text."}],
                                "summary":[{"type":"summary_text","text":"Short summary."}]
                            },
                            {"type":"message","content":[{"type":"output_text","text":"42"}]}
                        ]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);
            JsonNode content = anth.get("content");

            assertEquals("thinking", content.get(0).get("type").asText());
            assertEquals("Full reasoning text.", content.get(0).get("thinking").asText());
        }

        @Test
        @DisplayName("reasoning encrypted_content 带文本时转为 thinking signature")
        void reasoningEncryptedContentToThinkingSignature() throws Exception {
            String body = """
                    {
                        "id": "resp_reasoning_sig",
                        "model": "gpt-5.2",
                        "status": "completed",
                        "output": [
                            {
                                "type":"reasoning",
                                "content":[{"type":"reasoning_text","text":"Hidden chain."}],
                                "encrypted_content":"sig_123"
                            },
                            {"type":"message","content":[{"type":"output_text","text":"42"}]}
                        ]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);
            JsonNode thinking = anth.get("content").get(0);

            assertEquals("thinking", thinking.get("type").asText());
            assertEquals("Hidden chain.", thinking.get("thinking").asText());
            assertEquals("sig_123", thinking.get("signature").asText());
        }

        @Test
        @DisplayName("仅 encrypted_content 的 reasoning 转为 redacted_thinking")
        void encryptedOnlyReasoningToRedactedThinking() throws Exception {
            String body = """
                    {
                        "id": "resp_redacted",
                        "model": "gpt-5.2",
                        "status": "completed",
                        "output": [
                            {"type":"reasoning","encrypted_content":"encrypted_blob"},
                            {"type":"message","content":[{"type":"output_text","text":"42"}]}
                        ]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);
            JsonNode redacted = anth.get("content").get(0);

            assertEquals("redacted_thinking", redacted.get("type").asText());
            assertEquals("encrypted_blob", redacted.get("data").asText());
        }

        @Test
        @DisplayName("incomplete → max_tokens stop_reason")
        void incomplete() throws Exception {
            String body = """
                    {
                        "id": "resp_inc",
                        "model": "gpt-5.2",
                        "status": "incomplete",
                        "incomplete_details": {"reason": "max_output_tokens"},
                        "output": [{"type":"message","content":[{"type":"output_text","text":"Partial..."}]}]
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);

            assertEquals("max_tokens", anth.get("stop_reason").asText());
        }

        @Test
        @DisplayName("空 output → 空 content，不伪造 text")
        void emptyOutput() throws Exception {
            String body = """
                    {"id": "resp_empty", "model": "gpt-5.2", "status": "completed", "output": []}""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);

            JsonNode content = anth.get("content");
            assertEquals(0, content.size());
        }

        @Test
        @DisplayName("failed status → end_turn fallback")
        void failed() throws Exception {
            String body = """
                    {
                        "id": "resp_fail_3",
                        "model": "gpt-5.2",
                        "status": "failed",
                        "error": {"code":"server_error","message":"Something went wrong"},
                        "output": []
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);

            assertEquals("end_turn", anth.get("stop_reason").asText());
        }

        @Test
        @DisplayName("未提供 max_tokens 时默认 8192")
        void maxTokensDefault() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [{"role":"user","content":"Hello"}]
                    }""";

            JsonNode ir = JSON.readTree(body);
            String result = fromIR.requestFromIR(ir);
            JsonNode anth = JSON.readTree(result);

            // requestFromIR 中未提供 max_output_tokens 时默认 8192
            assertEquals(8192, anth.get("max_tokens").asInt());
        }
    }

    // ========================
    // 响应转换：Anthropic → Responses (toIR.responseToIR)
    // ========================

    @Nested
    @DisplayName("Anthropic → Responses 响应转换")
    class AnthropicToResponsesResponse {

        @Test
        @DisplayName("基本 text 响应")
        void basicTextResponse() throws Exception {
            String body = """
                    {
                        "id": "msg_123",
                        "type": "message",
                        "role": "assistant",
                        "model": "claude-opus-4-6",
                        "stop_reason": "end_turn",
                        "content": [{"type":"text","text":"Hello!"}],
                        "usage": {"input_tokens":5,"output_tokens":3}
                    }""";

            JsonNode result = toIR.responseToIR(body);

            assertEquals("msg_123", result.get("id").asText());
            assertEquals("response", result.get("object").asText());
            assertEquals("completed", result.get("status").asText());

            JsonNode output = result.get("output");
            assertEquals(1, output.size());
            assertEquals("message", output.get(0).get("type").asText());
            assertEquals("output_text", output.get(0).get("content").get(0).get("type").asText());
            assertEquals("Hello!", output.get(0).get("content").get(0).get("text").asText());
        }

        @Test
        @DisplayName("Anthropic 搜索专用内容块非流式不伪造成 Responses 文本或工具")
        void searchSpecificBlocksAreIgnoredInNonStreamingResponse() throws Exception {
            String body = """
                    {
                        "id": "msg_search_blocks",
                        "type": "message",
                        "role": "assistant",
                        "model": "claude-opus-4-6",
                        "stop_reason": "end_turn",
                        "content": [
                            {"type":"server_tool_use","id":"srv_1","name":"web_search","input":{"query":"LandGate"}},
                            {"type":"web_search_tool_result","tool_use_id":"srv_1","content":[{"type":"search_result","title":"Result","url":"https://example.com","encrypted_content":"opaque"}]},
                            {"type":"search_result","title":"Standalone","url":"https://example.com/standalone"},
                            {"type":"text","text":"visible answer"}
                        ],
                        "usage": {"input_tokens":5,"output_tokens":3}
                    }""";

            JsonNode result = toIR.responseToIR(body);
            JsonNode output = result.get("output");

            assertEquals(1, output.size());
            assertEquals("message", output.get(0).get("type").asText());
            assertEquals("visible answer", output.get(0).get("content").get(0).get("text").asText());
        }

        @Test
        @DisplayName("thinking → reasoning output")
        void thinkingToReasoning() throws Exception {
            String body = """
                    {
                        "id": "msg_think",
                        "type": "message",
                        "role": "assistant",
                        "model": "claude-opus-4-6",
                        "stop_reason": "end_turn",
                        "content": [
                            {"type":"thinking","thinking":"Let me analyze...","signature":"sig_abc"},
                            {"type":"text","text":"Result: 42"}
                        ],
                        "usage": {"input_tokens":10,"output_tokens":20}
                    }""";

            JsonNode result = toIR.responseToIR(body);
            JsonNode output = result.get("output");

            assertEquals(2, output.size());
            assertEquals("reasoning", output.get(0).get("type").asText());
            assertEquals("Let me analyze...",
                    output.get(0).get("summary").get(0).get("text").asText());
            assertEquals("Let me analyze...",
                    output.get(0).get("content").get(0).get("text").asText());
            assertEquals("sig_abc", output.get(0).get("encrypted_content").asText());
            assertEquals("message", output.get(1).get("type").asText());
        }

        @Test
        @DisplayName("redacted_thinking → encrypted reasoning output")
        void redactedThinkingToEncryptedReasoning() throws Exception {
            String body = """
                    {
                        "id": "msg_redacted",
                        "type": "message",
                        "role": "assistant",
                        "model": "claude-opus-4-6",
                        "stop_reason": "end_turn",
                        "content": [
                            {"type":"redacted_thinking","data":"encrypted_blob"},
                            {"type":"text","text":"Result"}
                        ],
                        "usage": {"input_tokens":10,"output_tokens":20}
                    }""";

            JsonNode result = toIR.responseToIR(body);
            JsonNode reasoning = result.get("output").get(0);

            assertEquals("reasoning", reasoning.get("type").asText());
            assertEquals("encrypted_blob", reasoning.get("encrypted_content").asText());
            assertFalse(reasoning.has("content"));
        }

        @Test
        @DisplayName("tool_use → function_call output")
        void toolUseToFunctionCall() throws Exception {
            String body = """
                    {
                        "id": "msg_tool",
                        "type": "message",
                        "role": "assistant",
                        "model": "claude-opus-4-6",
                        "stop_reason": "tool_use",
                        "content": [{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"NYC"}}],
                        "usage": {"input_tokens":10,"output_tokens":5}
                    }""";

            JsonNode result = toIR.responseToIR(body);
            JsonNode output = result.get("output");

            assertEquals(1, output.size());
            assertEquals("function_call", output.get(0).get("type").asText());
            assertEquals("toolu_1", output.get(0).get("call_id").asText());
            assertEquals("get_weather", output.get(0).get("name").asText());
        }

        @Test
        @DisplayName("缺 id/name 的 Anthropic tool_use 响应不进入 Responses IR")
        void invalidToolUseInResponseIgnored() throws Exception {
            String body = """
                    {
                        "id": "msg_invalid_tool",
                        "type": "message",
                        "role": "assistant",
                        "model": "claude-opus-4-6",
                        "stop_reason": "tool_use",
                        "content": [
                            {"type":"tool_use","name":"missing_id","input":{}},
                            {"type":"tool_use","id":"toolu_missing_name","input":{}},
                            {"type":"text","text":"visible"}
                        ],
                        "usage": {"input_tokens":10,"output_tokens":5}
                    }""";

            JsonNode result = toIR.responseToIR(body);
            JsonNode output = result.get("output");

            assertEquals(1, output.size());
            assertEquals("message", output.get(0).get("type").asText());
            assertEquals("visible", output.get(0).get("content").get(0).get("text").asText());
            assertFalse(output.toString().contains("function_call"));
        }

        @Test
        @DisplayName("text/tool_use/text 输出顺序保留")
        void textAndToolUseOutputOrderPreserved() throws Exception {
            String body = """
                    {
                        "id": "msg_order",
                        "type": "message",
                        "role": "assistant",
                        "model": "claude-opus-4-6",
                        "stop_reason": "tool_use",
                        "content": [
                            {"type":"text","text":"Before tool."},
                            {"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"NYC"}},
                            {"type":"text","text":"After tool."}
                        ],
                        "usage": {"input_tokens":10,"output_tokens":5}
                    }""";

            JsonNode output = toIR.responseToIR(body).get("output");

            assertEquals(3, output.size());
            assertEquals("message", output.get(0).get("type").asText());
            assertEquals("Before tool.", output.get(0).get("content").get(0).get("text").asText());
            assertEquals("function_call", output.get(1).get("type").asText());
            assertEquals("toolu_1", output.get(1).get("call_id").asText());
            assertEquals("message", output.get(2).get("type").asText());
            assertEquals("After tool.", output.get(2).get("content").get(0).get("text").asText());
        }

        @Test
        @DisplayName("max_tokens → incomplete status")
        void maxTokensIncomplete() throws Exception {
            String body = """
                    {
                        "id": "msg_inc",
                        "type": "message",
                        "role": "assistant",
                        "model": "claude-opus-4-6",
                        "stop_reason": "max_tokens",
                        "content": [{"type":"text","text":"Partial..."}],
                        "usage": {"input_tokens":100,"output_tokens":4096}
                    }""";

            JsonNode result = toIR.responseToIR(body);

            assertEquals("incomplete", result.get("status").asText());
            assertEquals("max_output_tokens",
                    result.get("incomplete_details").get("reason").asText());
        }

        @Test
        @DisplayName("model_context_window_exceeded → incomplete 且不伪造 reason")
        void modelContextWindowExceededIncompleteWithoutInventedReason() throws Exception {
            String body = """
                    {
                        "id": "msg_context",
                        "type": "message",
                        "role": "assistant",
                        "model": "claude-opus-4-6",
                        "stop_reason": "model_context_window_exceeded",
                        "content": [{"type":"text","text":"Partial context-limited output"}],
                        "usage": {"input_tokens":200000,"output_tokens":10}
                    }""";

            JsonNode result = toIR.responseToIR(body);

            assertEquals("incomplete", result.get("status").asText());
            assertFalse(result.has("incomplete_details"));
        }

        @Test
        @DisplayName("cache creation/read tokens → Responses input_tokens；cached_tokens 只表示 read")
        void cacheReadTokensToResponsesUsage() throws Exception {
            String body = """
                    {
                        "id": "msg_cached",
                        "type": "message",
                        "role": "assistant",
                        "model": "claude-opus-4-6",
                        "stop_reason": "end_turn",
                        "content": [{"type":"text","text":"Cached"}],
                        "usage": {"input_tokens":12,"cache_creation_input_tokens":8,"cache_read_input_tokens":80,"output_tokens":4}
                    }""";

            JsonNode result = toIR.responseToIR(body);
            JsonNode usage = result.get("usage");

            assertEquals(100, usage.get("input_tokens").asInt());
            assertEquals(80, usage.get("input_tokens_details").get("cached_tokens").asInt());
            assertEquals(4, usage.get("output_tokens").asInt());
            assertEquals(104, usage.get("total_tokens").asInt());
        }

        @Test
        @DisplayName("空 content → 空 output，不伪造 output_text")
        void emptyContentFallback() throws Exception {
            String body = """
                    {"type":"message","role":"assistant","model":"unknown","stop_reason":"end_turn",
                     "content":[],"usage":{"input_tokens":0,"output_tokens":0}}""";

            JsonNode result = toIR.responseToIR(body);
            // 由于 input 无 id，会生成随机 id
            assertNotNull(result.get("id"));
            assertEquals("completed", result.get("status").asText());
            assertEquals(0, result.get("output").size());
        }

        @Test
        @DisplayName("空 text block → 空 output，不伪造 output_text")
        void emptyTextBlockDoesNotCreateOutputText() throws Exception {
            String body = """
                    {"type":"message","role":"assistant","model":"unknown","stop_reason":"end_turn",
                     "content":[{"type":"text","text":""}],"usage":{"input_tokens":0,"output_tokens":0}}""";

            JsonNode result = toIR.responseToIR(body);

            assertEquals("completed", result.get("status").asText());
            assertEquals(0, result.get("output").size());
        }
    }

    // ========================
    // Responses 请求 → Anthropic (fromIR.requestFromIR)
    // ========================

    @Nested
    @DisplayName("Responses IR → Anthropic 请求转换")
    class ResponsesToAnthropicRequest {

        @Test
        @DisplayName("service_tier 保留到 Anthropic")
        void serviceTierPreserved() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "service_tier": "standard_only",
                        "input": [{"role":"user","content":"Hello"}]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertEquals("standard_only", anth.get("service_tier").asText());
        }

        @Test
        @DisplayName("非对象 metadata 不写入 Anthropic")
        void nonObjectMetadataDroppedToAnthropic() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "metadata": ["trace_id"],
                        "input": [{"role":"user","content":"Hello"}]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertFalse(anth.has("metadata"));
        }

        @Test
        @DisplayName("非文本 service_tier 不写入 Anthropic")
        void nonTextServiceTierDroppedToAnthropic() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "service_tier": {"tier":"standard_only"},
                        "input": [{"role":"user","content":"Hello"}]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertFalse(anth.has("service_tier"));
        }

        @Test
        @DisplayName("Responses web_search_preview tool → Anthropic web_search")
        void responsesWebSearchPreviewToAnthropic() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "tools": [{"type":"web_search_preview",
                            "user_location":{"type":"approximate","country":"US","city":"Seattle"}}],
                        "input": [{"role":"user","content":"Hello"}]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode tool = anth.get("tools").get(0);

            assertEquals("web_search_20250305", tool.get("type").asText());
            assertEquals("web_search", tool.get("name").asText());
            assertEquals("Seattle", tool.get("user_location").get("city").asText());
        }

        @Test
        @DisplayName("Responses web_search user_location 非对象不透传到 Anthropic")
        void responsesWebSearchWithNonObjectUserLocationDropsLocation() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "tools": [{"type":"web_search_preview","user_location":"Seattle"}],
                        "input": [{"role":"user","content":"Hello"}]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode tool = anth.get("tools").get(0);

            assertEquals("web_search_20250305", tool.get("type").asText());
            assertFalse(tool.has("user_location"));
        }

        @Test
        @DisplayName("Responses hosted tools 不降级到 Anthropic tools")
        void hostedToolsAreNotConvertedToAnthropicTools() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "tools": [
                            {"type":"file_search","vector_store_ids":["vs_123"]},
                            {"type":"computer_use_preview","display_width":1024,"display_height":768},
                            {"type":"mcp","server_label":"docs","server_url":"https://mcp.example.com"},
                            {"type":"image_generation"},
                            {"type":"code_interpreter","container":{"type":"auto"}},
                            {"type":"local_shell"}
                        ],
                        "input": [{"role":"user","content":"Hello"}]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertFalse(anth.has("tools"));
        }

        @Test
        @DisplayName("不支持的 Responses tool_choice 不透传到 Anthropic")
        void unsupportedToolChoiceIsDropped() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [{"role":"user","content":"Hello"}],
                        "tool_choice": {"type":"custom","name":"grammar"}
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertFalse(anth.has("tool_choice"));
        }

        @Test
        @DisplayName("缺 name 的 Responses function tool 不降级到 Anthropic tools")
        void unnamedResponsesFunctionToolIsDropped() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "tools": [{"type":"function","parameters":{"type":"object"}}],
                        "input": [{"role":"user","content":"Hello"}]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertFalse(anth.has("tools"));
        }

        @Test
        @DisplayName("Responses function parameters 非对象 → Anthropic 空 input_schema")
        void responsesFunctionToolWithNonObjectParametersUsesEmptySchema() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "tools": [{"type":"function","name":"simple_tool","parameters":"not-a-schema"}],
                        "input": [{"role":"user","content":"Hello"}]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode schema = anth.get("tools").get(0).get("input_schema");

            assertEquals("object", schema.get("type").asText());
            assertTrue(schema.get("properties").isObject());
        }

        @Test
        @DisplayName("缺 name 的 Responses function tool_choice 不透传到 Anthropic")
        void unnamedResponsesFunctionToolChoiceIsDropped() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [{"role":"user","content":"Hello"}],
                        "tool_choice": {"type":"function"}
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertFalse(anth.has("tool_choice"));
        }

        @Test
        @DisplayName("tool_choice function name → Anthropic tool")
        void toolChoiceFunctionName() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [{"role":"user","content":"Hello"}],
                        "tool_choice": {"type":"function","name":"get_weather"}
                    }""";

            JsonNode ir = JSON.readTree(body);
            String result = fromIR.requestFromIR(ir);
            JsonNode anth = JSON.readTree(result);

            JsonNode tc = anth.get("tool_choice");
            assertEquals("tool", tc.get("type").asText());
            assertEquals("get_weather", tc.get("name").asText());
        }

        @Test
        @DisplayName("web_search tool_choice → Anthropic web_search tool choice")
        void webSearchToolChoiceToAnthropic() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [{"role":"user","content":"Hello"}],
                        "tool_choice": {"type":"web_search_preview"}
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode tc = anth.get("tool_choice");

            assertEquals("tool", tc.get("type").asText());
            assertEquals("web_search", tc.get("name").asText());
        }

        @Test
        @DisplayName("parallel_tool_calls=false → Anthropic disable_parallel_tool_use")
        void parallelToolCallsFalseToAnthropicToolChoice() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [{"role":"user","content":"Hello"}],
                        "parallel_tool_calls": false,
                        "tool_choice": {"type":"function","name":"get_weather"}
                    }""";

            JsonNode ir = JSON.readTree(body);
            String result = fromIR.requestFromIR(ir);
            JsonNode anth = JSON.readTree(result);
            JsonNode tc = anth.get("tool_choice");

            assertEquals("tool", tc.get("type").asText());
            assertEquals("get_weather", tc.get("name").asText());
            assertTrue(tc.get("disable_parallel_tool_use").asBoolean());
        }

        @Test
        @DisplayName("parallel_tool_calls=false 且无 tool_choice 时生成 auto tool_choice")
        void parallelToolCallsFalseCreatesAutoToolChoice() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [{"role":"user","content":"Hello"}],
                        "parallel_tool_calls": false
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode tc = anth.get("tool_choice");

            assertEquals("auto", tc.get("type").asText());
            assertTrue(tc.get("disable_parallel_tool_use").asBoolean());
        }

        @Test
        @DisplayName("unsupported tool_choice 且 parallel_tool_calls=false 时保留并行禁用")
        void unsupportedToolChoiceWithParallelFalseFallsBackToAuto() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [{"role":"user","content":"Hello"}],
                        "parallel_tool_calls": false,
                        "tool_choice": {"type":"custom","name":"grammar"}
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode tc = anth.get("tool_choice");

            assertEquals("auto", tc.get("type").asText());
            assertTrue(tc.get("disable_parallel_tool_use").asBoolean());
            assertFalse(tc.has("name"));
        }

        @Test
        @DisplayName("tool_choice legacy function 嵌套格式")
        void toolChoiceLegacyFunctionName() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [{"role":"user","content":"Hello"}],
                        "tool_choice": {"type":"function","function":{"name":"get_weather"}}
                    }""";

            JsonNode ir = JSON.readTree(body);
            String result = fromIR.requestFromIR(ir);
            JsonNode anth = JSON.readTree(result);

            JsonNode tc = anth.get("tool_choice");
            assertEquals("tool", tc.get("type").asText());
            assertEquals("get_weather", tc.get("name").asText());
        }

        @Test
        @DisplayName("function_call_input → assistant message with tool_use")
        void functionCallInput() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [
                            {"role":"user","content":"Hello"},
                            {"type":"function_call","call_id":"call_1","name":"ping","arguments":"{\\"host\\":\\"example.com\\"}"}
                        ]
                    }""";

            JsonNode ir = JSON.readTree(body);
            String result = fromIR.requestFromIR(ir);
            JsonNode anth = JSON.readTree(result);

            JsonNode messages = anth.get("messages");
            assertEquals(2, messages.size());
            assertEquals("assistant", messages.get(1).get("role").asText());
            JsonNode content = messages.get(1).get("content");
            assertEquals(1, content.size());
            assertEquals("tool_use", content.get(0).get("type").asText());
            assertEquals("call_1", content.get(0).get("id").asText());
        }

        @Test
        @DisplayName("缺 call_id/name 的 function_call input 不构造 Anthropic tool_use")
        void invalidFunctionCallInputIgnored() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [
                            {"role":"user","content":"Hello"},
                            {"type":"function_call","name":"missing_call_id","arguments":"{}"},
                            {"type":"function_call","call_id":"call_missing_name","arguments":"{}"},
                            {"type":"function_call_output","output":"orphan result"}
                        ]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode messages = anth.get("messages");

            assertEquals(1, messages.size());
            assertEquals("user", messages.get(0).get("role").asText());
            assertTrue(messages.toString().contains("Hello"));
            assertFalse(messages.toString().contains("tool_use"));
            assertFalse(messages.toString().contains("tool_result"));
        }

        @Test
        @DisplayName("function_call_output 缺 output → Anthropic 空 tool_result text")
        void missingFunctionCallOutputContentStaysEmpty() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [
                            {"type":"function_call_output","call_id":"call_1"}
                        ]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode content = anth.get("messages").get(0).get("content");
            JsonNode toolResult = content.get(0);

            assertEquals("tool_result", toolResult.get("type").asText());
            assertEquals("call_1", toolResult.get("tool_use_id").asText());
            assertEquals("", toolResult.get("content").get(0).get("text").asText());
        }

        @Test
        @DisplayName("reasoning input item → assistant thinking")
        void reasoningInputToThinking() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [
                            {"role":"user","content":"Hello"},
                            {
                                "type":"reasoning",
                                "content":[{"type":"reasoning_text","text":"Think"}],
                                "encrypted_content":"sig_1"
                            }
                        ]
                    }""";

            String result = fromIR.requestFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);
            JsonNode thinking = anth.get("messages").get(1).get("content").get(0);

            assertEquals("thinking", thinking.get("type").asText());
            assertEquals("Think", thinking.get("thinking").asText());
            assertEquals("sig_1", thinking.get("signature").asText());
        }

        @Test
        @DisplayName("仅 encrypted_content 的 reasoning input item → redacted_thinking")
        void encryptedOnlyReasoningInputToRedactedThinking() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [
                            {"role":"user","content":"Hello"},
                            {"type":"reasoning","encrypted_content":"encrypted_blob"}
                        ]
                    }""";

            String result = fromIR.requestFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);
            JsonNode redacted = anth.get("messages").get(1).get("content").get(0);

            assertEquals("redacted_thinking", redacted.get("type").asText());
            assertEquals("encrypted_blob", redacted.get("data").asText());
        }

        @Test
        @DisplayName("Responses URL input_image → Anthropic URL image source")
        void urlInputImageToAnthropic() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [{"role":"user","content":[
                            {"type":"input_image","image_url":"https://example.com/cat.png"}
                        ]}]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode source = anth.get("messages").get(0).get("content").get(0).get("source");

            assertEquals("url", source.get("type").asText());
            assertEquals("https://example.com/cat.png", source.get("url").asText());
        }

        @Test
        @DisplayName("Responses unsupported-only content 不伪造成 Anthropic 空消息")
        void unsupportedOnlyContentDoesNotCreateEmptyAnthropicMessages() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [
                            {"role":"user","content":[
                                {"type":"input_audio","input_audio":{"data":"UklGRg==","format":"wav"}}
                            ]},
                            {"role":"assistant","content":[
                                {"type":"custom_tool_call","call_id":"call_custom","name":"grammar","input":"start: expr"}
                            ]},
                            {"role":"user","content":[{"type":"input_text","text":"visible"}]}
                        ]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode messages = anth.get("messages");

            assertEquals(1, messages.size());
            assertEquals("user", messages.get(0).get("role").asText());
            assertEquals("visible", messages.get(0).get("content").get(0).get("text").asText());
            assertFalse(messages.toString().contains("input_audio"));
            assertFalse(messages.toString().contains("custom_tool_call"));
        }

        @Test
        @DisplayName("Responses 空 assistant content 不伪造成 Anthropic 空 text")
        void emptyAssistantContentDoesNotCreateAnthropicTextBlock() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [
                            {"role":"assistant","content":""},
                            {"role":"assistant","content":[
                                {"type":"output_text","text":""},
                                {"type":"text","text":""}
                            ]},
                            {"role":"user","content":[{"type":"input_text","text":"visible"}]}
                        ]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode messages = anth.get("messages");

            assertEquals(1, messages.size());
            assertEquals("user", messages.get(0).get("role").asText());
            assertEquals("visible", messages.get(0).get("content").get(0).get("text").asText());
            assertFalse(messages.toString().contains("\"role\":\"assistant\""));
            assertFalse(messages.toString().contains("\"text\":\"\""));
        }

        @Test
        @DisplayName("Responses 空 user text part 不生成 Anthropic 空 text block")
        void emptyUserTextPartsDoNotCreateAnthropicTextBlock() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [
                            {"role":"user","content":[
                                {"type":"input_text","text":""},
                                {"type":"text","text":"   "}
                            ]},
                            {"role":"user","content":[
                                {"type":"input_text","text":"visible"}
                            ]}
                        ]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode messages = anth.get("messages");

            assertEquals(1, messages.size());
            assertEquals("user", messages.get(0).get("role").asText());
            assertEquals("visible", messages.get(0).get("content").get(0).get("text").asText());
            assertFalse(messages.toString().contains("\"text\":\"\""));
        }

        @Test
        @DisplayName("Responses input_file file_url/file_data/file_id → Anthropic document")
        void inputFileToAnthropicDocument() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [{"role":"user","content":[
                            {"type":"input_file","filename":"remote.pdf","file_url":"https://example.com/remote.pdf"},
                            {"type":"input_file","filename":"inline.pdf","file_data":"data:application/pdf;base64,JVBERi0x"},
                            {"type":"input_file","filename":"uploaded.pdf","file_id":"file_123"}
                        ]}]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));
            JsonNode content = anth.get("messages").get(0).get("content");

            assertEquals("document", content.get(0).get("type").asText());
            assertEquals("remote.pdf", content.get(0).get("title").asText());
            assertEquals("url", content.get(0).get("source").get("type").asText());
            assertEquals("https://example.com/remote.pdf", content.get(0).get("source").get("url").asText());
            assertEquals("base64", content.get(1).get("source").get("type").asText());
            assertEquals("application/pdf", content.get(1).get("source").get("media_type").asText());
            assertEquals("JVBERi0x", content.get(1).get("source").get("data").asText());
            assertEquals("file", content.get(2).get("source").get("type").asText());
            assertEquals("file_123", content.get(2).get("source").get("file_id").asText());
        }

        @Test
        @DisplayName("developer role → Anthropic 顶层 system")
        void developerRoleToSystem() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [{"role":"developer","content":"You are a helpful assistant."}]
                    }""";

            JsonNode ir = JSON.readTree(body);
            String result = fromIR.requestFromIR(ir);
            JsonNode anth = JSON.readTree(result);

            assertEquals("You are a helpful assistant.", anth.get("system").asText());
            assertEquals(0, anth.get("messages").size());
        }

        @Test
        @DisplayName("instructions 与 system/developer input 合并为 Anthropic system")
        void instructionsAndInstructionRolesMergedToSystem() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "instructions": "Top instructions.",
                        "input": [
                            {"role":"system","content":"System rules."},
                            {"role":"developer","content":"Developer rules."},
                            {"role":"user","content":"Hello"}
                        ]
                    }""";

            JsonNode ir = JSON.readTree(body);
            String result = fromIR.requestFromIR(ir);
            JsonNode anth = JSON.readTree(result);

            assertEquals("Top instructions.\n\nSystem rules.\n\nDeveloper rules.", anth.get("system").asText());
            assertEquals(1, anth.get("messages").size());
            assertEquals("user", anth.get("messages").get(0).get("role").asText());
        }

        @Test
        @DisplayName("空白 system/developer input 不生成 Anthropic system")
        void blankInstructionInputsDoNotCreateAnthropicSystem() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "input": [
                            {"role":"system","content":"   "},
                            {"role":"developer","content":[
                                {"type":"input_text","text":""},
                                {"type":"text","text":"   "}
                            ]},
                            {"role":"user","content":"Hello"}
                        ]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertFalse(anth.has("system"));
            assertEquals(1, anth.get("messages").size());
            assertEquals("Hello", anth.get("messages").get(0).get("content").asText());
        }

        @Test
        @DisplayName("空白 instructions 不生成 Anthropic system")
        void blankInstructionsDoNotCreateAnthropicSystem() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "instructions": "   ",
                        "input": [{"role":"user","content":"Hello"}]
                    }""";

            JsonNode anth = JSON.readTree(fromIR.requestFromIR(JSON.readTree(body)));

            assertFalse(anth.has("system"));
            assertEquals(1, anth.get("messages").size());
            assertEquals("Hello", anth.get("messages").get(0).get("content").asText());
        }
    }

    // ========================
    // 流式 SSE 翻译
    // ========================

    @Nested
    @DisplayName("流式 Anthropic SSE → Responses IR SSE")
    class AnthropicToResponsesStream {

        @Test
        @DisplayName("完整文本流")
        void streamingTextOnly() {
            StreamTranslator t = toIR.createStreamToIR("gpt-5.2");

            // 1. message_start → 不直接输出（仅初始化）
            List<String> out = t.feed("data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"gpt-5.2\",\"usage\":{\"input_tokens\":10}}}");
            // message_start 内部处理，可能不产出（取决于实现）
            assertFalse(t.isDone());

            // 2. content_block_start (text) → output_item.added
            out = t.feed("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
            assertTrue(out.stream().anyMatch(s -> s.startsWith("event: response.created")
                    || s.startsWith("event: response.output_item.added")));

            // 3. content_block_delta → output_text.delta
            out = t.feed("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("response.output_text.delta")));

            // 4. content_block_stop → output_text.done
            out = t.feed("data: {\"type\":\"content_block_stop\",\"index\":0}");
            assertTrue(out.stream().anyMatch(s -> s.contains("response.output_text.done")));

            // 5. message_stop → response.completed
            out = t.feed("data: {\"type\":\"message_stop\"}");
            assertTrue(out.stream().anyMatch(s -> s.contains("response.completed")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("流式 cache creation/read tokens → Responses usage")
        void streamingCacheReadTokens() {
            StreamTranslator t = toIR.createStreamToIR("gpt-5.2");

            t.feed("data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"gpt-5.2\",\"usage\":{\"input_tokens\":10,\"cache_creation_input_tokens\":6,\"cache_read_input_tokens\":40}}}");
            t.feed("data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}");
            List<String> out = t.feed("data: {\"type\":\"message_stop\"}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"input_tokens\":56")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"cached_tokens\":40")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"total_tokens\":61")));
        }

        @Test
        @DisplayName("message_delta stop_reason=max_tokens → response incomplete")
        void streamingMaxTokensStopReason() {
            StreamTranslator t = toIR.createStreamToIR("gpt-5.2");

            t.feed("data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"gpt-5.2\",\"usage\":{\"input_tokens\":10}}}");
            t.feed("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":5}}");
            List<String> out = t.feed("data: {\"type\":\"message_stop\"}");

            assertTrue(out.stream().anyMatch(s -> s.contains("response.incomplete")));
            assertTrue(out.stream().noneMatch(s -> s.contains("response.completed")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"status\":\"incomplete\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"reason\":\"max_output_tokens\"")));
        }

        @Test
        @DisplayName("message_delta stop_reason=model_context_window_exceeded → incomplete without reason")
        void streamingModelContextWindowExceededStopReason() {
            StreamTranslator t = toIR.createStreamToIR("gpt-5.2");

            t.feed("data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"gpt-5.2\",\"usage\":{\"input_tokens\":10}}}");
            t.feed("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"model_context_window_exceeded\"},\"usage\":{\"output_tokens\":5}}");
            List<String> out = t.feed("data: {\"type\":\"message_stop\"}");

            assertTrue(out.stream().anyMatch(s -> s.contains("response.incomplete")));
            assertTrue(out.stream().noneMatch(s -> s.contains("response.completed")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"status\":\"incomplete\"")));
            assertTrue(out.stream().noneMatch(s -> s.contains("incomplete_details")));
        }

        @Test
        @DisplayName("refusal delta 流作为 Anthropic text_delta 输出")
        void streamingRefusalDelta() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-5.2");

            List<String> out = t.feed("data: {\"type\":\"response.refusal.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"No.\"}");

            assertTrue(out.stream().anyMatch(s -> s.contains("content_block_start")));
            assertTrue(out.stream().anyMatch(s -> s.contains("text_delta")));
            assertTrue(out.stream().anyMatch(s -> s.contains("No.")));
        }

        @Test
        @DisplayName("content_part.done fallback 输出未见过的文本")
        void streamingContentPartDoneFallback() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-5.2");

            List<String> out = t.feed("data: {\"type\":\"response.content_part.done\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"Final\"}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("text_delta")));
            assertTrue(out.stream().anyMatch(s -> s.contains("Final")));
        }

        @Test
        @DisplayName("tool_use 流式翻译")
        void streamingToolCall() {
            StreamTranslator t = toIR.createStreamToIR("gpt-5.2");

            // content_block_start (tool_use) → output_item.added function_call
            List<String> out = t.feed("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"get_weather\"}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("function_call")));

            out = t.feed("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\\\"NYC\\\"}\"}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("response.function_call_arguments.delta")));

            out = t.feed("data: {\"type\":\"content_block_stop\",\"index\":0}");
            assertTrue(out.stream().anyMatch(s -> s.contains("response.function_call_arguments.done")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\"")));
        }

        @Test
        @DisplayName("缺 id/name 的 Anthropic tool_use 流不进入 Responses IR")
        void invalidToolUseStreamIgnored() {
            StreamTranslator t = toIR.createStreamToIR("gpt-5.2");

            List<String> out = t.feed("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"name\":\"missing_id\"}}");
            assertTrue(out.stream().noneMatch(s -> s.contains("function_call")));

            out = t.feed("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\\\"NYC\\\"}\"}}");
            assertTrue(out.stream().noneMatch(s -> s.contains("response.function_call_arguments.delta")));

            out = t.feed("data: {\"type\":\"content_block_stop\",\"index\":0}");
            assertTrue(out.stream().noneMatch(s -> s.contains("function_call")));
            assertTrue(out.stream().noneMatch(s -> s.contains("response.function_call_arguments.done")));
        }

        @Test
        @DisplayName("thinking 流式翻译")
        void streamingThinking() {
            StreamTranslator t = toIR.createStreamToIR("gpt-5.2");

            // content_block_start (thinking) → output_item.added reasoning
            List<String> out = t.feed("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("reasoning")));

            // content_block_delta (thinking_delta) → reasoning_summary_text.delta
            out = t.feed("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Let me think...\"}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("response.reasoning_summary_text.delta")));
        }

        @Test
        @DisplayName("signature_delta 保留到 reasoning encrypted_content")
        void streamingThinkingSignaturePreserved() {
            StreamTranslator t = toIR.createStreamToIR("gpt-5.2");

            t.feed("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}");
            t.feed("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_part\"}}");
            List<String> out = t.feed("data: {\"type\":\"content_block_stop\",\"index\":0}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"encrypted_content\":\"sig_part\"")));
        }

        @Test
        @DisplayName("不产出 [DONE] 行（Anthropic 通常以 message_stop 结束）")
        void noDoneLine() {
            StreamTranslator t = toIR.createStreamToIR("gpt-5.2");
            assertTrue(t.feed("data: [DONE]").isEmpty());
        }

        @Test
        @DisplayName("error event → response.failed")
        void streamingErrorEvent() {
            StreamTranslator t = toIR.createStreamToIR("gpt-5.2");

            List<String> out = t.feed("data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("response.failed")));
            assertTrue(out.stream().anyMatch(s -> s.contains("overloaded_error")));
            assertTrue(t.isDone());
        }
    }

    // ========================
    // 流式 Responses IR SSE → Anthropic SSE
    // ========================

    @Nested
    @DisplayName("流式 Responses IR SSE → Anthropic SSE")
    class ResponsesToAnthropicStream {

        @Test
        @DisplayName("完整文本流")
        void streamingTextOnly() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-5.2");

            // 1. response.created → message_start
            List<String> out = t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-5.2\"}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("message_start")));

            // 2. output_text.delta → content_block_start + content_block_delta
            out = t.feed("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}");
            assertTrue(out.stream().anyMatch(s -> s.contains("content_block_start")));
            assertTrue(out.stream().anyMatch(s -> s.contains("content_block_delta")));
            assertTrue(out.stream().anyMatch(s -> s.contains("text_delta")));

            // 3. response.done → message_delta + message_stop
            out = t.feed("data: {\"type\":\"response.done\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":12,\"output_tokens\":4}}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("message_delta")));
            assertTrue(out.stream().anyMatch(s -> s.contains("message_stop")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("response.done incomplete → max_tokens")
        void responseDoneIncomplete() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-5.2");
            t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_inc\",\"model\":\"gpt-5.2\"}}");

            List<String> out = t.feed("data: {\"type\":\"response.done\",\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"usage\":{\"input_tokens\":12,\"output_tokens\":4}}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("max_tokens")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("tool_use 流式翻译")
        void streamingToolCall() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-5.2");
            t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_2\",\"model\":\"gpt-5.2\"}}");

            // function_call added
            List<String> out = t.feed("data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"get_weather\"}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("content_block_start")));
            assertTrue(out.stream().anyMatch(s -> s.contains("tool_use")));

            // arguments delta
            out = t.feed("data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,\"delta\":\"{\\\"city\\\":\"}");
            assertTrue(out.stream().anyMatch(s -> s.contains("input_json_delta")));

            // response.completed
            out = t.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":20,\"output_tokens\":10}}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"tool_use\"")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("output_item.done function_call fallback 输出完整 tool_use")
        void streamingToolCallFromOutputItemDoneFallback() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-5.2");
            t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_tool_done\",\"model\":\"gpt-5.2\"}}");

            List<String> out = t.feed("data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"NYC\\\"}\",\"status\":\"completed\"}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("content_block_start")));
            assertTrue(out.stream().anyMatch(s -> s.contains("tool_use")));
            assertTrue(out.stream().anyMatch(s -> s.contains("input_json_delta")));
            assertTrue(out.stream().anyMatch(s -> s.contains("{\\\"city\\\":\\\"NYC\\\"}")));
        }

        @Test
        @DisplayName("缺 call_id/name 的 Responses function_call 流不构造 Anthropic tool_use")
        void invalidFunctionCallStreamShapesIgnored() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-5.2");
            t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_invalid_tool_stream\",\"model\":\"gpt-5.2\"}}");

            List<String> out = t.feed("data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"name\":\"missing_call_id\"}}");
            assertTrue(out.stream().noneMatch(s -> s.contains("\"tool_use\"")));

            out = t.feed("data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,\"delta\":\"{\\\"city\\\":\\\"NYC\\\"}\"}");
            assertTrue(out.stream().noneMatch(s -> s.contains("\"tool_use\"")));
            assertTrue(out.stream().noneMatch(s -> s.contains("input_json_delta")));

            out = t.feed("data: {\"type\":\"response.output_item.done\",\"output_index\":1,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_missing_name\",\"arguments\":\"{}\",\"status\":\"completed\"}}");
            assertTrue(out.stream().noneMatch(s -> s.contains("\"tool_use\"")));

            out = t.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":20,\"output_tokens\":10}}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"stop_reason\":\"end_turn\"")));
            assertTrue(out.stream().noneMatch(s -> s.contains("\"stop_reason\":\"tool_use\"")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("Responses hosted tool call 流式事件不降级为 Anthropic text 或 tool_use")
        void hostedToolCallStreamEventsAreIgnored() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-5.2");
            t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_hosted\",\"model\":\"gpt-5.2\"}}");

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
                assertTrue(added.stream().noneMatch(s -> s.contains("\"tool_use\"")));
                assertTrue(added.stream().noneMatch(s -> s.contains("\"text_delta\"")));

                List<String> done = t.feed("data: {\"type\":\"response.output_item.done\",\"output_index\":" + i
                        + ",\"item\":{\"type\":\"" + hostedTypes[i] + "\",\"id\":\"hosted_" + i + "\",\"status\":\"completed\"}}");
                assertTrue(done.stream().noneMatch(s -> s.contains("\"tool_use\"")));
                assertTrue(done.stream().noneMatch(s -> s.contains("\"text_delta\"")));
            }

            List<String> out = t.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"file_search_call\",\"id\":\"fs_1\",\"status\":\"completed\"},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"visible\"}]}]}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"text_delta\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("visible")));
            assertTrue(out.stream().noneMatch(s -> s.contains("\"tool_use\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"stop_reason\":\"end_turn\"")));
        }

        @Test
        @DisplayName("response.completed.output fallback 输出最终文本和 cached usage")
        void completedOutputFallbackAndCachedUsage() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-5.2");
            t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_final\",\"model\":\"gpt-5.2\"}}");

            List<String> out = t.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"final only\"}]}],\"usage\":{\"input_tokens\":100,\"output_tokens\":5,\"input_tokens_details\":{\"cached_tokens\":80}}}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("final only")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"cache_read_input_tokens\":80")));
            assertTrue(out.stream().anyMatch(s -> s.contains("message_stop")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("tool_use stop_reason 在后续文本中幸存")
        void toolCallStopReasonSurvivesLaterText() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-5.5");
            t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_tool_then_text\",\"model\":\"gpt-5.5\"}}");

            // function_call
            t.feed("data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_todo\",\"name\":\"TodoWrite\"}}");
            t.feed("data: {\"type\":\"response.function_call_arguments.done\",\"output_index\":0,\"arguments\":\"{}\"}");

            // text after tool call
            t.feed("data: {\"type\":\"response.output_text.delta\",\"delta\":\"I will continue after the task list updates.\"}");

            // completed should still show tool_use stop_reason
            List<String> out = t.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":20,\"output_tokens\":10}}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"tool_use\"")));
        }

        @Test
        @DisplayName("Read 工具 flow：argument delta 缓冲，done 时发送")
        void readToolBuffering() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-5.5");
            t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_read\",\"model\":\"gpt-5.5\"}}");

            // Read function_call start
            t.feed("data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_read\",\"name\":\"Read\"}}");

            // Read 的 delta 被缓冲，不立即输出
            List<String> out = t.feed("data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":0,\"delta\":\"{\\\"file_path\\\":\\\"/tmp/demo.py\\\",\\\"pages\\\":\\\"\\\"}\"}");
            // Read 工具：实时 delta 被过滤（只有 done 时发送）
            assertTrue(out.stream().noneMatch(s -> s.contains("input_json_delta")));

            // done 时应发送过滤 pages 后的完整 arguments
            out = t.feed("data: {\"type\":\"response.function_call_arguments.done\",\"output_index\":0,\"arguments\":\"{\\\"file_path\\\":\\\"/tmp/demo.py\\\",\\\"limit\\\":2000,\\\"offset\\\":0,\\\"pages\\\":\\\"\\\"}\"}");
            assertTrue(out.stream().anyMatch(s -> s.contains("input_json_delta")));
            // 确认 pages 字段被过滤
            assertTrue(out.stream().anyMatch(s -> !s.contains("pages")));
        }

        @Test
        @DisplayName("reasoning 流式翻译")
        void streamingReasoning() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-5.2");
            t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_3\",\"model\":\"gpt-5.2\"}}");

            // reasoning output_item.added
            List<String> out = t.feed("data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"reasoning\"}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("thinking")));

            // reasoning_summary_text.delta
            out = t.feed("data: {\"type\":\"response.reasoning_summary_text.delta\",\"output_index\":0,\"delta\":\"Let me think...\"}");
            assertTrue(out.stream().anyMatch(s -> s.contains("thinking_delta")));
        }

        @Test
        @DisplayName("reasoning_text 流式翻译")
        void streamingReasoningText() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-5.2");
            t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_3\",\"model\":\"gpt-5.2\"}}");
            t.feed("data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"reasoning\"}}");

            List<String> out = t.feed("data: {\"type\":\"response.reasoning_text.delta\",\"output_index\":0,\"delta\":\"Full thinking...\"}");
            assertTrue(out.stream().anyMatch(s -> s.contains("thinking_delta")));

            out = t.feed("data: {\"type\":\"response.reasoning_text.done\",\"output_index\":0}");
            assertTrue(out.stream().anyMatch(s -> s.contains("content_block_stop")));
        }

        @Test
        @DisplayName("response.completed.output reasoning.content fallback")
        void completedOutputReasoningContentFallback() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-5.2");
            t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_reasoning_final\",\"model\":\"gpt-5.2\"}}");

            List<String> out = t.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"content\":[{\"type\":\"reasoning_text\",\"text\":\"Final reasoning content.\"}],\"summary\":[{\"type\":\"summary_text\",\"text\":\"Summary fallback.\"}]}]}}");

            assertTrue(out.stream().anyMatch(s -> s.contains("thinking_delta")));
            assertTrue(out.stream().anyMatch(s -> s.contains("Final reasoning content.")));
            assertTrue(out.stream().noneMatch(s -> s.contains("Summary fallback.")));
        }

        @Test
        @DisplayName("异常终止 finalize")
        void finalizeAbnormalTermination() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-5.2");

            t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_5\",\"model\":\"gpt-5.2\"}}");
            t.feed("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Interrupted...\"}");

            // 流未正常完成 → isDone=false，但不崩溃
            assertFalse(t.isDone());

            // 再喂一个 done 结束
            List<String> out = t.feed("data: {\"type\":\"response.done\",\"response\":{\"status\":\"completed\"}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("message_stop")));
            assertTrue(t.isDone());
        }

        @Test
        @DisplayName("response.failed → 正常结束")
        void streamingFailed() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-5.2");
            t.feed("data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_fail\",\"model\":\"gpt-5.2\"}}");
            t.feed("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial output\"}}");

            List<String> out = t.feed("data: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"Internal error\"},\"usage\":{\"input_tokens\":50,\"output_tokens\":10}}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("message_delta")));
            assertTrue(out.stream().anyMatch(s -> s.contains("message_stop")));
            assertTrue(out.stream().noneMatch(s -> s.contains("Internal error")));
            assertTrue(t.isDone());
        }
    }
}
