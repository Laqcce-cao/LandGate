package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic prompt caching 兼容策略。
 * <p>
 * 对齐 sub2api 的 cache_control 上限保护：最多保留 4 个缓存断点，超限时优先移除
 * tools，其次 messages，最后 system；thinking 块上的 cache_control 直接移除。
 */
@Slf4j
final class AnthropicCacheControlPolicy {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CACHE_CONTROL_BLOCKS = 4;

    private AnthropicCacheControlPolicy() {}

    static String enforceLimit(String body) {
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
        } catch (Exception e) {
            log.debug("Failed to enforce Anthropic cache_control limit: {}", e.getMessage());
            return body;
        }
    }

    private static boolean collectAndStripInvalid(JsonNode root,
                                                  List<ObjectNode> system,
                                                  List<ObjectNode> messages,
                                                  List<ObjectNode> tools) {
        boolean modified = false;

        JsonNode systemNode = root.get("system");
        if (systemNode != null && systemNode.isArray()) {
            for (JsonNode item : systemNode) {
                if (hasCacheControl(item)) system.add((ObjectNode) item);
            }
        }

        JsonNode messagesNode = root.get("messages");
        if (messagesNode != null && messagesNode.isArray()) {
            for (JsonNode message : messagesNode) {
                JsonNode content = message.get("content");
                if (content == null || !content.isArray()) continue;
                for (JsonNode block : content) {
                    if (!block.isObject() || !block.has("cache_control")) continue;
                    String type = block.path("type").asText("");
                    ObjectNode blockObj = (ObjectNode) block;
                    if ("thinking".equals(type) || "redacted_thinking".equals(type)) {
                        blockObj.remove("cache_control");
                        modified = true;
                    } else {
                        messages.add(blockObj);
                    }
                }
            }
        }

        JsonNode toolsNode = root.get("tools");
        if (toolsNode != null && toolsNode.isArray()) {
            for (JsonNode tool : toolsNode) {
                if (hasCacheControl(tool)) tools.add((ObjectNode) tool);
            }
        }

        return modified;
    }

    private static boolean hasCacheControl(JsonNode node) {
        return node != null && node.isObject() && node.has("cache_control");
    }

    private static int removeCacheControlReverse(List<ObjectNode> nodes, int remaining) {
        for (int i = nodes.size() - 1; i >= 0 && remaining > 0; i--) {
            if (nodes.get(i).has("cache_control")) {
                nodes.get(i).remove("cache_control");
                remaining--;
            }
        }
        return remaining;
    }

    private static int removeCacheControlForward(List<ObjectNode> nodes, int remaining) {
        for (ObjectNode node : nodes) {
            if (remaining <= 0) break;
            if (node.has("cache_control")) {
                node.remove("cache_control");
                remaining--;
            }
        }
        return remaining;
    }
}
