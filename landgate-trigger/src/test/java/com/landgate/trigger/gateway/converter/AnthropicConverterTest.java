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
                        "messages": [{"role": "user", "content": "Hello"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);

            assertEquals("gpt-5.2", resp.get("model").asText());
            assertTrue(resp.get("stream").asBoolean());
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
        @DisplayName("thinking 块被忽略")
        void thinkingIgnored() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [
                            {"role": "user", "content": "Hello"},
                            {"role": "assistant", "content": [{"type":"thinking","thinking":"deep thought"},{"type":"text","text":"Hi!"}]},
                            {"role": "user", "content": "More"}
                        ]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode input = resp.get("input");
            // user + assistant(text only) + user = 3
            assertEquals(3, input.size());

            // assistant content 只包含 text
            JsonNode assistantContent = input.get(1).get("content");
            assertEquals(1, assistantContent.size());
            assertEquals("output_text", assistantContent.get(0).get("type").asText());
            assertEquals("Hi!", assistantContent.get(0).get("text").asText());
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
            assertEquals("(empty)", input.get(2).get("output").asText());

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
        @DisplayName("web_search 前缀 tool → web_search type")
        void webSearchTool() throws Exception {
            String body = """
                    {
                        "model": "gpt-5.2",
                        "max_tokens": 1024,
                        "messages": [{"role": "user", "content": "search"}],
                        "tools": [{"type":"web_search_20250305","name":"web_search"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode tools = resp.get("tools");
            assertEquals(1, tools.size());
            assertEquals("web_search", tools.get(0).get("type").asText());
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
        @DisplayName("空 output → 空 text 块兜底")
        void emptyOutput() throws Exception {
            String body = """
                    {"id": "resp_empty", "model": "gpt-5.2", "status": "completed", "output": []}""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode anth = JSON.readTree(result);

            JsonNode content = anth.get("content");
            assertEquals(1, content.size());
            assertEquals("text", content.get(0).get("type").asText());
            assertEquals("", content.get(0).get("text").asText());
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
                            {"type":"thinking","thinking":"Let me analyze..."},
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
            assertEquals("message", output.get(1).get("type").asText());
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
        @DisplayName("cache_read_input_tokens → Responses cached_tokens 且计入 input_tokens")
        void cacheReadTokensToResponsesUsage() throws Exception {
            String body = """
                    {
                        "id": "msg_cached",
                        "type": "message",
                        "role": "assistant",
                        "model": "claude-opus-4-6",
                        "stop_reason": "end_turn",
                        "content": [{"type":"text","text":"Cached"}],
                        "usage": {"input_tokens":12,"cache_read_input_tokens":80,"output_tokens":4}
                    }""";

            JsonNode result = toIR.responseToIR(body);
            JsonNode usage = result.get("usage");

            assertEquals(92, usage.get("input_tokens").asInt());
            assertEquals(80, usage.get("input_tokens_details").get("cached_tokens").asInt());
            assertEquals(4, usage.get("output_tokens").asInt());
            assertEquals(96, usage.get("total_tokens").asInt());
        }

        @Test
        @DisplayName("空 content → 空 text 块兜底")
        void emptyContentFallback() throws Exception {
            String body = """
                    {"type":"message","role":"assistant","model":"unknown","stop_reason":"end_turn",
                     "content":[],"usage":{"input_tokens":0,"output_tokens":0}}""";

            JsonNode result = toIR.responseToIR(body);
            // 由于 input 无 id，会生成随机 id
            assertNotNull(result.get("id"));
            assertEquals("completed", result.get("status").asText());
        }
    }

    // ========================
    // Responses 请求 → Anthropic (fromIR.requestFromIR)
    // ========================

    @Nested
    @DisplayName("Responses IR → Anthropic 请求转换")
    class ResponsesToAnthropicRequest {

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
        @DisplayName("流式 cache_read_input_tokens → Responses cached_tokens")
        void streamingCacheReadTokens() {
            StreamTranslator t = toIR.createStreamToIR("gpt-5.2");

            t.feed("data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"gpt-5.2\",\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":40}}}");
            t.feed("data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}");
            List<String> out = t.feed("data: {\"type\":\"message_stop\"}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"input_tokens\":50")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"cached_tokens\":40")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"total_tokens\":55")));
        }

        @Test
        @DisplayName("message_delta stop_reason=max_tokens → response incomplete")
        void streamingMaxTokensStopReason() {
            StreamTranslator t = toIR.createStreamToIR("gpt-5.2");

            t.feed("data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"gpt-5.2\",\"usage\":{\"input_tokens\":10}}}");
            t.feed("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":5}}");
            List<String> out = t.feed("data: {\"type\":\"message_stop\"}");

            assertTrue(out.stream().anyMatch(s -> s.contains("\"status\":\"incomplete\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"reason\":\"max_output_tokens\"")));
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
            assertTrue(t.isDone());
        }
    }
}
