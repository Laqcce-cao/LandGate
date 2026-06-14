package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProtocolTranslationService 路线测试")
class ProtocolTranslationServiceRouteTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProtocolTranslationService service = translationService();

    @Test
    @DisplayName("Messages -> Responses 通过 Responses IR 转换")
    void messagesToResponsesViaResponsesIr() throws Exception {
        String body = """
                {
                  "model":"claude-sonnet-4-5",
                  "max_tokens":64,
                  "stream":true,
                  "system":"Project rules",
                  "messages":[{"role":"user","content":"Hello"}]
                }
                """;

        JsonNode out = JSON.readTree(service.translateRequest(body, "messages", "responses"));

        assertEquals("claude-sonnet-4-5", out.get("model").asText());
        assertEquals(128, out.get("max_output_tokens").asInt());
        assertTrue(out.get("stream").asBoolean());
        assertTrue(out.has("input"));
        assertEquals("message", out.get("input").get(0).get("type").asText());
        assertEquals("developer", out.get("input").get(0).get("role").asText());
        assertEquals("user", out.get("input").get(1).get("role").asText());
    }

    @Test
    @DisplayName("Responses -> Messages 通过 Responses IR 转换")
    void responsesToMessagesViaResponsesIr() throws Exception {
        String body = """
                {
                  "model":"gpt-5.4",
                  "max_output_tokens":128,
                  "input":[
                    {"type":"message","role":"developer","content":[{"type":"input_text","text":"Project rules"}]},
                    {"type":"message","role":"user","content":[{"type":"input_text","text":"Hello"}]}
                  ]
                }
                """;

        JsonNode out = JSON.readTree(service.translateRequest(body, "responses", "messages"));

        assertEquals("gpt-5.4", out.get("model").asText());
        assertEquals(128, out.get("max_tokens").asInt());
        assertEquals("Project rules", out.get("system").asText());
        assertEquals("user", out.get("messages").get(0).get("role").asText());
    }

    @Test
    @DisplayName("Chat Completions -> Responses 通过 Responses IR 转换")
    void chatCompletionsToResponsesViaResponsesIr() throws Exception {
        String body = """
                {
                  "model":"gpt-5.4",
                  "max_completion_tokens":256,
                  "messages":[
                    {"role":"system","content":"Project rules"},
                    {"role":"user","content":"Hello"}
                  ]
                }
                """;

        JsonNode out = JSON.readTree(service.translateRequest(body, "chat_completions", "responses"));

        assertEquals("gpt-5.4", out.get("model").asText());
        assertEquals(256, out.get("max_output_tokens").asInt());
        assertTrue(out.has("input"));
        assertEquals("system", out.get("input").get(0).get("role").asText());
        assertEquals("user", out.get("input").get(1).get("role").asText());
    }

    @Test
    @DisplayName("Responses -> Chat Completions 通过 Responses IR 转换")
    void responsesToChatCompletionsViaResponsesIr() throws Exception {
        String body = """
                {
                  "model":"gpt-5.4",
                  "max_output_tokens":128,
                  "input":[
                    {"type":"message","role":"developer","content":[{"type":"input_text","text":"Project rules"}]},
                    {"type":"message","role":"user","content":[{"type":"input_text","text":"Hello"}]}
                  ]
                }
                """;

        JsonNode out = JSON.readTree(service.translateRequest(body, "responses", "chat_completions"));

        assertEquals("gpt-5.4", out.get("model").asText());
        assertEquals(128, out.get("max_completion_tokens").asInt());
        assertEquals("developer", out.get("messages").get(0).get("role").asText());
        assertEquals("Project rules", out.get("messages").get(0).get("content").asText());
        assertEquals("user", out.get("messages").get(1).get("role").asText());
    }

    @Test
    @DisplayName("Chat Completions -> Messages 通过 Responses IR 跨协议转换")
    void chatCompletionsToMessagesViaResponsesIr() throws Exception {
        String body = """
                {
                  "model":"gpt-5.4",
                  "max_completion_tokens":64,
                  "messages":[
                    {"role":"system","content":"Project rules"},
                    {"role":"user","content":"Hello"}
                  ]
                }
                """;

        JsonNode out = JSON.readTree(service.translateRequest(body, "chat_completions", "messages"));

        assertEquals("gpt-5.4", out.get("model").asText());
        assertEquals(128, out.get("max_tokens").asInt());
        assertEquals("Project rules", out.get("system").asText());
        assertEquals("user", out.get("messages").get(0).get("role").asText());
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
