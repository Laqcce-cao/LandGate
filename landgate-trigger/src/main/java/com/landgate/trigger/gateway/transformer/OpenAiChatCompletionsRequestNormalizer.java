package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.gateway.OpenAiChatCompletionsBodyPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Normalizes public OpenAI Chat Completions API-key requests before forwarding upstream.
 */
@Slf4j
@Component
public class OpenAiChatCompletionsRequestNormalizer {

    private static final ObjectMapper JSON = new ObjectMapper();

    public String normalize(String body) {
        return normalize(body, null);
    }

    public String normalize(String body, AccountEntity account) {
        try {
            JsonNode parsed = JSON.readTree(body);
            if (!(parsed instanceof ObjectNode root)) return body;
            OpenAiModelMappingRequestNormalizer.apply(root, account, OpenAiChatCompletionsBodyPolicy.FIELD_MODEL);
            ensureStreamUsage(root);
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Failed to normalize OpenAI Chat Completions request body", e);
            return body;
        }
    }

    /**
     * OpenAI Chat Completions streaming responses do not include usage unless requested.
     * This mirrors Sub2API's raw Chat route behavior.
     */
    private static void ensureStreamUsage(ObjectNode root) {
        if (!root.path(OpenAiChatCompletionsBodyPolicy.FIELD_STREAM).asBoolean(false)) return;
        JsonNode streamOptionsNode = root.get(OpenAiChatCompletionsBodyPolicy.FIELD_STREAM_OPTIONS);
        ObjectNode streamOptions = streamOptionsNode instanceof ObjectNode objectNode
                ? objectNode
                : JSON.createObjectNode();
        streamOptions.put(OpenAiChatCompletionsBodyPolicy.FIELD_INCLUDE_USAGE, true);
        root.set(OpenAiChatCompletionsBodyPolicy.FIELD_STREAM_OPTIONS, streamOptions);
    }
}
