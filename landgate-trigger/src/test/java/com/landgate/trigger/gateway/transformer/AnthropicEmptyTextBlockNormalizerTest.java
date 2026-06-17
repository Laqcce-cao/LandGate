package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("AnthropicEmptyTextBlockNormalizer")
class AnthropicEmptyTextBlockNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("strips top-level and nested empty text blocks")
    void stripsTopLevelAndNestedEmptyTextBlocks() throws Exception {
        String body = """
                {"messages":[{"role":"user","content":[
                  {"type":"text","text":"hello"},
                  {"type":"text","text":""},
                  {"type":"tool_result","tool_use_id":"t1","content":[
                    {"type":"text","text":"ok"},
                    {"type":"text","text":""}
                  ]}
                ]}]}""";

        JsonNode root = JSON.readTree(AnthropicEmptyTextBlockNormalizer.normalize(body));
        JsonNode content = root.path("messages").get(0).path("content");

        assertEquals(2, content.size());
        assertEquals("hello", content.get(0).path("text").asText());
        assertEquals(1, content.get(1).path("content").size());
        assertEquals("ok", content.get(1).path("content").get(0).path("text").asText());
    }

    @Test
    @DisplayName("recurses through nested tool_result content")
    void recursesThroughNestedToolResultContent() throws Exception {
        String body = """
                {"messages":[{"role":"user","content":[
                  {"type":"tool_result","tool_use_id":"t1","content":[
                    {"type":"tool_result","tool_use_id":"t2","content":[
                      {"type":"text","text":""},
                      {"type":"text","text":"deep"}
                    ]}
                  ]}
                ]}]}""";

        JsonNode root = JSON.readTree(AnthropicEmptyTextBlockNormalizer.normalize(body));
        JsonNode deepContent = root.path("messages").get(0)
                .path("content").get(0)
                .path("content").get(0)
                .path("content");

        assertEquals(1, deepContent.size());
        assertEquals("deep", deepContent.get(0).path("text").asText());
    }

    @Test
    @DisplayName("preserves original body when unchanged")
    void preservesOriginalBodyWhenUnchanged() {
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}]}";

        assertSame(body, AnthropicEmptyTextBlockNormalizer.normalize(body));
    }
}
