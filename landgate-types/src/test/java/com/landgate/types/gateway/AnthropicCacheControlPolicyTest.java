package com.landgate.types.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AnthropicCacheControlPolicy tests")
class AnthropicCacheControlPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("cache_control over limit drops tools before messages and system")
    void enforceLimitDropsToolsBeforeMessagesAndSystem() throws Exception {
        String body = """
                {
                    "system": [
                        {"type":"text","text":"sys","cache_control":{"type":"ephemeral"}}
                    ],
                    "messages": [
                        {"role":"user","content":[
                            {"type":"text","text":"m1","cache_control":{"type":"ephemeral"}},
                            {"type":"text","text":"m2","cache_control":{"type":"ephemeral"}},
                            {"type":"text","text":"m3","cache_control":{"type":"ephemeral"}}
                        ]}
                    ],
                    "tools": [
                        {"name":"a","input_schema":{},"cache_control":{"type":"ephemeral"}}
                    ]
                }""";

        JsonNode result = JSON.readTree(AnthropicCacheControlPolicy.enforceLimit(body));

        assertEquals(4, countCacheControl(result));
        assertTrue(result.get("system").get(0).has("cache_control"));
        assertTrue(result.get("messages").get(0).get("content").get(0).has("cache_control"));
        assertTrue(result.get("messages").get(0).get("content").get(1).has("cache_control"));
        assertTrue(result.get("messages").get(0).get("content").get(2).has("cache_control"));
        assertFalse(result.get("tools").get(0).has("cache_control"));
    }

    @Test
    @DisplayName("thinking content block cache_control is stripped")
    void stripsInvalidThinkingCacheControl() throws Exception {
        String body = """
                {
                    "messages": [
                        {"role":"assistant","content":[
                            {"type":"thinking","thinking":"x","cache_control":{"type":"ephemeral"}},
                            {"type":"text","text":"ok","cache_control":{"type":"ephemeral"}}
                        ]}
                    ]
                }""";

        JsonNode result = JSON.readTree(AnthropicCacheControlPolicy.enforceLimit(body));

        assertFalse(result.get("messages").get(0).get("content").get(0).has("cache_control"));
        assertTrue(result.get("messages").get(0).get("content").get(1).has("cache_control"));
    }

    private static int countCacheControl(JsonNode node) {
        int count = node.has("cache_control") ? 1 : 0;
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                count += countCacheControl(child);
            }
        }
        return count;
    }
}
