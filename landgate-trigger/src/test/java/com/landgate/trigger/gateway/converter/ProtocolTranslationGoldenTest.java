package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.types.gateway.GatewayProtocolIrPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProtocolTranslationService complex golden tests")
class ProtocolTranslationGoldenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProtocolTranslationService service = translationService();

    @Test
    @DisplayName("Chat request tool_calls survive Hub-and-Spoke translation to Anthropic Messages")
    void chatToolCallsTranslateToAnthropicMessages() throws Exception {
        String chatBody = """
                {
                  "model":"gpt-5.4",
                  "messages":[
                    {"role":"user","content":"Call the function"},
                    {"role":"assistant","tool_calls":[
                      {"id":"call_1","type":"function","function":{"name":"ping","arguments":"{\\"host\\":\\"example.com\\"}"}}
                    ]},
                    {"role":"tool","tool_call_id":"call_1","content":"pong"}
                  ],
                  "tools":[{"type":"function","function":{"name":"ping","description":"Ping a host","parameters":{"type":"object"},"strict":true}}]
                }""";

        JsonNode messages = JSON.readTree(service.translateRequest(chatBody, "chat_completions", "messages"));

        assertEquals("gpt-5.4", messages.get("model").asText());
        assertEquals("ping", messages.get("tools").get(0).get("name").asText());
        assertEquals("Ping a host", messages.get("tools").get(0).get("description").asText());
        assertEquals("object", messages.get("tools").get(0).get("input_schema").get("type").asText());
        assertEquals(3, messages.get("messages").size());
        JsonNode toolUse = messages.get("messages").get(1).get("content").get(0);
        assertEquals("assistant", messages.get("messages").get(1).get("role").asText());
        assertEquals("tool_use", toolUse.get("type").asText());
        assertEquals("call_1", toolUse.get("id").asText());
        assertEquals("ping", toolUse.get("name").asText());
        assertEquals("example.com", toolUse.get("input").get("host").asText());
        JsonNode toolResult = messages.get("messages").get(2).get("content").get(0);
        assertEquals("user", messages.get("messages").get(2).get("role").asText());
        assertEquals("tool_result", toolResult.get("type").asText());
        assertEquals("call_1", toolResult.get("tool_use_id").asText());
        assertEquals("pong", toolResult.get("content").asText());
    }

    @Test
    @DisplayName("Chat stop sequences survive Hub-and-Spoke translation to Anthropic Messages but are stripped for raw Responses")
    void chatStopSequencesTranslateOnlyWhereSupported() throws Exception {
        String chatBody = """
                {
                  "model":"gpt-5.4",
                  "stop":["</end>","DONE"],
                  "messages":[{"role":"user","content":"Hi"}]
                }""";

        JsonNode messages = JSON.readTree(service.translateRequest(chatBody, "chat_completions", "messages"));
        JsonNode responses = JSON.readTree(service.translateRequest(chatBody, "chat_completions", "responses"));

        assertEquals("</end>", messages.get("stop_sequences").get(0).asText());
        assertEquals("DONE", messages.get("stop_sequences").get(1).asText());
        assertFalse(responses.has(GatewayProtocolIrPolicy.FIELD_STOP_SEQUENCES));
        assertFalse(responses.has("stop"));
    }

    @Test
    @DisplayName("Responses response reasoning/tool calls/cache survive translation to Chat Completions")
    void responsesReasoningToolCallsAndCacheTranslateToChat() throws Exception {
        String responsesBody = """
                {
                  "id":"resp_complex",
                  "object":"response",
                  "model":"gpt-5.4",
                  "status":"completed",
                  "output":[
                    {"type":"reasoning","summary":[{"type":"summary_text","text":"I should call the tool."}]},
                    {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Calling tool."}]},
                    {"type":"function_call","call_id":"call_weather","name":"get_weather","arguments":"{\\"city\\":\\"NYC\\"}"}
                  ],
                  "usage":{
                    "input_tokens":100,
                    "output_tokens":10,
                    "total_tokens":110,
                    "input_tokens_details":{"cached_tokens":80}
                  }
                }""";

        JsonNode chat = JSON.readTree(service.translateResponse(responsesBody, "responses", "chat_completions"));
        JsonNode message = chat.get("choices").get(0).get("message");
        JsonNode toolCall = message.get("tool_calls").get(0);

        assertEquals("chat.completion", chat.get("object").asText());
        assertEquals("Calling tool.", message.get("content").asText());
        assertEquals("I should call the tool.", message.get("reasoning_content").asText());
        assertEquals("tool_calls", chat.get("choices").get(0).get("finish_reason").asText());
        assertEquals("call_weather", toolCall.get("id").asText());
        assertEquals("function", toolCall.get("type").asText());
        assertEquals("get_weather", toolCall.get("function").get("name").asText());
        assertEquals("{\"city\":\"NYC\"}", toolCall.get("function").get("arguments").asText());
        assertEquals(80, chat.get("usage").get("prompt_tokens_details").get("cached_tokens").asInt());
    }

    @Test
    @DisplayName("Responses response reasoning/cache survive translation to Anthropic Messages")
    void responsesReasoningAndCacheTranslateToMessages() throws Exception {
        String responsesBody = """
                {
                  "id":"resp_reasoning_cache",
                  "object":"response",
                  "model":"gpt-5.4",
                  "status":"completed",
                  "output":[
                    {"type":"reasoning","content":[{"type":"reasoning_text","text":"Full reasoning."}],"summary":[{"type":"summary_text","text":"Short reasoning."}]},
                    {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Final answer."}]}
                  ],
                  "usage":{
                    "input_tokens":100,
                    "output_tokens":10,
                    "input_tokens_details":{"cached_tokens":80}
                  }
                }""";

        JsonNode messages = JSON.readTree(service.translateResponse(responsesBody, "responses", "messages"));
        JsonNode content = messages.get("content");
        JsonNode usage = messages.get("usage");

        assertEquals("message", messages.get("type").asText());
        assertEquals("thinking", content.get(0).get("type").asText());
        assertEquals("Short reasoning.", content.get(0).get("thinking").asText());
        assertEquals("text", content.get(1).get("type").asText());
        assertEquals("Final answer.", content.get(1).get("text").asText());
        assertEquals(20, usage.get("input_tokens").asInt());
        assertEquals(80, usage.get("cache_read_input_tokens").asInt());
        assertEquals(10, usage.get("output_tokens").asInt());
    }

    private static ProtocolTranslationService translationService() {
        ConverterRegistry registry = new ConverterRegistry();
        registry.register(List.of(
                new AnthropicConverter(),
                new ResponsesConverter(),
                new ChatCompletionsConverter()));
        return new ProtocolTranslationService(registry);
    }
}
