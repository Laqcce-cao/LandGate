package com.landgate.types.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic prompt caching compatibility policy.
 *
 * <p>Aligned with Sub2API cache_control limit behavior: keep at most four
 * cache breakpoints. When over the limit, remove tools first, then messages,
 * then system. cache_control on thinking blocks is always stripped.</p>
 */
public final class AnthropicCacheControlPolicy {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CACHE_CONTROL_BLOCKS = 4;

    private AnthropicCacheControlPolicy() {
    }

    public static String enforceLimit(String body) {
        if (body == null || body.isBlank()) return body;
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isObject()) return body;

            List<ObjectNode> system = new ArrayList<>();
            List<ObjectNode> messages = new ArrayList<>();
            List<ObjectNode> tools = new ArrayList<>();
            boolean modified = collectAndStripInvalid(root, system, messages, tools);

            int count = system.size() + messages.size() + tools.size();
            if (count <= MAX_CACHE_CONTROL_BLOCKS) {
                return modified ? JSON.writeValueAsString(root) : body;
            }

            int remaining = count - MAX_CACHE_CONTROL_BLOCKS;
            remaining = removeCacheControlReverse(tools, remaining);
            remaining = removeCacheControlForward(messages, remaining);
            removeCacheControlReverse(system, remaining);

            return JSON.writeValueAsString(root);
        } catch (Exception ignored) {
            return body;
        }
    }

    private static boolean collectAndStripInvalid(JsonNode root,
                                                  List<ObjectNode> system,
                                                  List<ObjectNode> messages,
                                                  List<ObjectNode> tools) {
        boolean modified = false;

        JsonNode systemNode = root.get(AnthropicMessagesBodyPolicy.FIELD_SYSTEM);
        if (systemNode != null && systemNode.isArray()) {
            for (JsonNode item : systemNode) {
                if (AnthropicMessagesBodyPolicy.hasCacheControl(item)) system.add((ObjectNode) item);
            }
        }

        JsonNode messagesNode = root.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGES);
        if (messagesNode != null && messagesNode.isArray()) {
            for (JsonNode message : messagesNode) {
                JsonNode content = message.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT);
                if (content == null || !content.isArray()) continue;
                for (JsonNode block : content) {
                    if (!block.isObject() || !AnthropicMessagesBodyPolicy.hasCacheControl(block)) continue;
                    String type = block.path(AnthropicMessagesBodyPolicy.FIELD_TYPE).asText("");
                    ObjectNode blockObj = (ObjectNode) block;
                    if (AnthropicThinkingPolicy.isThinkingBlockType(type)) {
                        blockObj.remove(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL);
                        modified = true;
                    } else {
                        messages.add(blockObj);
                    }
                }
            }
        }

        JsonNode toolsNode = root.get(AnthropicMessagesBodyPolicy.FIELD_TOOLS);
        if (toolsNode != null && toolsNode.isArray()) {
            for (JsonNode tool : toolsNode) {
                if (AnthropicMessagesBodyPolicy.hasCacheControl(tool)) tools.add((ObjectNode) tool);
            }
        }

        return modified;
    }

    private static int removeCacheControlReverse(List<ObjectNode> nodes, int remaining) {
        for (int i = nodes.size() - 1; i >= 0 && remaining > 0; i--) {
            if (AnthropicMessagesBodyPolicy.hasCacheControl(nodes.get(i))) {
                nodes.get(i).remove(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL);
                remaining--;
            }
        }
        return remaining;
    }

    private static int removeCacheControlForward(List<ObjectNode> nodes, int remaining) {
        for (ObjectNode node : nodes) {
            if (remaining <= 0) break;
            if (AnthropicMessagesBodyPolicy.hasCacheControl(node)) {
                node.remove(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL);
                remaining--;
            }
        }
        return remaining;
    }
}
