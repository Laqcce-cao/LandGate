package com.landgate.trigger.gateway.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.OpenAiCompatSessionPolicy;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sub2API-compatible full replay guard for OpenAI Anthropic Messages compat.
 *
 * <p>This class owns only Anthropic Messages JSON trimming. It does not decide
 * whether a route is eligible, resolve auth headers, attach previous_response_id,
 * or translate protocols.</p>
 */
@Slf4j
public class OpenAiAnthropicReplayGuard {

    private static final ObjectMapper JSON = new ObjectMapper();

    public TrimResult trimFullReplay(String body) {
        if (body == null || body.isBlank()) {
            return new TrimResult(body, false, 0);
        }
        try {
            JsonNode parsed = JSON.readTree(body);
            if (!(parsed instanceof ObjectNode root)) {
                return new TrimResult(body, false, 0);
            }
            JsonNode messages = root.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGES);
            if (!(messages instanceof ArrayNode messageArray)
                    || messageArray.size() <= OpenAiCompatSessionPolicy.ANTHROPIC_REPLAY_MAX_TAIL_MESSAGES) {
                return new TrimResult(body, false, messageCount(messages));
            }

            int start = messageArray.size() - OpenAiCompatSessionPolicy.ANTHROPIC_REPLAY_MAX_TAIL_MESSAGES;
            start = expandToolBoundary(messageArray, start);
            if (start <= 0) {
                return new TrimResult(body, false, messageArray.size());
            }

            ArrayNode trimmed = JSON.createArrayNode();
            for (int i = start; i < messageArray.size(); i++) {
                trimmed.add(messageArray.get(i));
            }
            root.set(AnthropicMessagesBodyPolicy.FIELD_MESSAGES, trimmed);
            return new TrimResult(JSON.writeValueAsString(root), true, trimmed.size());
        } catch (Exception e) {
            log.debug("OpenAI Anthropic compat full replay guard skipped invalid body", e);
            return new TrimResult(body, false, 0);
        }
    }

    private static int expandToolBoundary(ArrayNode messages, int start) {
        if (start <= 0 || start >= messages.size()) {
            return start;
        }

        Map<String, Integer> toolUseIndex = new HashMap<>();
        Map<String, Integer> toolResultIndex = new HashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            ToolIds ids = toolIds(messages.get(i));
            for (String id : ids.uses()) {
                toolUseIndex.putIfAbsent(id, i);
            }
            for (String id : ids.results()) {
                toolResultIndex.putIfAbsent(id, i);
            }
        }

        int current = start;
        while (true) {
            int next = current;
            for (int i = current; i < messages.size(); i++) {
                ToolIds ids = toolIds(messages.get(i));
                for (String id : ids.results()) {
                    Integer useIndex = toolUseIndex.get(id);
                    if (useIndex != null && useIndex < next) {
                        next = useIndex;
                    }
                }
                for (String id : ids.uses()) {
                    Integer resultIndex = toolResultIndex.get(id);
                    if (resultIndex != null && resultIndex < next) {
                        next = resultIndex;
                    }
                }
            }
            if (next == current) {
                return current;
            }
            current = next;
        }
    }

    private static ToolIds toolIds(JsonNode message) {
        JsonNode content = message == null ? null : message.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT);
        if (content == null || !content.isArray()) {
            return ToolIds.empty();
        }

        Set<String> uses = new HashSet<>();
        Set<String> results = new HashSet<>();
        for (JsonNode block : content) {
            String type = text(block, AnthropicMessagesBodyPolicy.FIELD_TYPE);
            if (AnthropicMessagesBodyPolicy.TYPE_TOOL_USE.equals(type)) {
                String id = text(block, AnthropicMessagesBodyPolicy.FIELD_ID);
                if (!id.isBlank()) uses.add(id);
            } else if (AnthropicMessagesBodyPolicy.TYPE_TOOL_RESULT.equals(type)) {
                String id = text(block, AnthropicMessagesBodyPolicy.FIELD_TOOL_USE_ID);
                if (!id.isBlank()) results.add(id);
            }
        }
        return new ToolIds(uses, results);
    }

    private static int messageCount(JsonNode messages) {
        return messages != null && messages.isArray() ? messages.size() : 0;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null) return "";
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    public record TrimResult(String body, boolean trimmed, int messagesAfterTrim) {
    }

    private record ToolIds(Set<String> uses, Set<String> results) {
        static ToolIds empty() {
            return new ToolIds(Set.of(), Set.of());
        }
    }
}
