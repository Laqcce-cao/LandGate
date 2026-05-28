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
            assertTrue(resp.get("stream").asBoolean()); // 始终强制 true
            assertFalse(resp.get("store").asBoolean());

            JsonNode input = resp.get("input");
            assertEquals(1, input.size());
            assertEquals("user", input.get(0).get("role").asText());
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
        @DisplayName("max_tokens → max_output_tokens（最小 128）")
        void maxTokens() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "max_tokens": 100,
                        "messages": [{"role": "user", "content": "Hi"}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            // 低于 minMaxOutputTokens(128)，应被 clamp 到 128
            assertEquals(128, resp.get("max_output_tokens").asInt());
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
        @DisplayName("image_url → input_image")
        void imageURL() throws Exception {
            String body = """
                    {
                        "model": "gpt-4o",
                        "messages": [{"role": "user", "content": [
                            {"type":"text","text":"Describe this"},
                            {"type":"image_url","image_url":{"url":"data:image/png;base64,abc123"}}
                        ]}]
                    }""";

            JsonNode resp = toIR.requestToIR(body);
            JsonNode content = resp.get("input").get(0).get("content");

            assertEquals(2, content.size());
            assertEquals("input_text", content.get(0).get("type").asText());
            assertEquals("Describe this", content.get(0).get("text").asText());
            assertEquals("input_image", content.get(1).get("type").asText());
            assertEquals("data:image/png;base64,abc123", content.get(1).get("image_url").asText());
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
        @DisplayName("assistant 数组 content 保留 <thinking> 标签")
        void assistantThinkingTagPreserved() throws Exception {
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
            assertEquals(2, input.size());

            JsonNode content = input.get(1).get("content");
            assertEquals(1, content.size());
            assertEquals("output_text", content.get(0).get("type").asText());
            String text = content.get(0).get("text").asText();
            assertTrue(text.contains("<thinking>internal plan</thinking>"));
            assertTrue(text.contains("final answer"));
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
                        "status": "completed",
                        "output": [{"type":"message","content":[{"type":"output_text","text":"Hello, world!"}]}],
                        "usage": {"input_tokens":10,"output_tokens":5,"total_tokens":15}
                    }""";

            String result = fromIR.responseFromIR(JSON.readTree(body));
            JsonNode chat = JSON.readTree(result);

            assertEquals("chat.completion", chat.get("object").asText());
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
                        "model": "gpt-4o",
                        "choices": [{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}],
                        "usage": {"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}
                    }""";

            JsonNode result = toIR.responseToIR(body);

            assertEquals("chatcmpl-123", result.get("id").asText());
            assertEquals("response", result.get("object").asText());
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

            assertEquals(2, output.size()); // function_call + message
            assertEquals("function_call", output.get(0).get("type").asText());
            assertEquals("call_1", output.get(0).get("call_id").asText());
            assertEquals("ping", output.get(0).get("name").asText());
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
        @DisplayName("developer role → system")
        void developerRoleToSystem() throws Exception {
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
            assertEquals("system", messages.get(0).get("role").asText());
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
        @DisplayName("response.done incomplete → length")
        void responseDoneIncomplete() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.done\",\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"usage\":{\"input_tokens\":13,\"output_tokens\":7}}}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"length\"")));
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
        @DisplayName("reasoning delta 流")
        void reasoningDelta() {
            StreamTranslator t = fromIR.createStreamFromIR("gpt-4o");

            List<String> out = t.feed("data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"Thinking...\"}");
            assertTrue(out.stream().anyMatch(s -> s.contains("\"reasoning_content\"")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"Thinking...\"")));
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
