package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.gateway.OpenAiChatCompletionsBodyPolicy;
import com.landgate.types.gateway.OpenAiFastPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Normalizes public OpenAI Chat Completions API-key requests before forwarding upstream.
 */
@Slf4j
@Component
public class OpenAiChatCompletionsRequestNormalizer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final OpenAiFastPolicyProvider fastPolicyProvider;

    public OpenAiChatCompletionsRequestNormalizer() {
        this(null);
    }

    @Autowired
    OpenAiChatCompletionsRequestNormalizer(OpenAiFastPolicyProvider fastPolicyProvider) {
        this.fastPolicyProvider = fastPolicyProvider;
    }

    public String normalize(String body) {
        return normalize(body, null);
    }

    public String normalize(String body, AccountEntity account) {
        try {
            JsonNode parsed = JSON.readTree(body);
            if (!(parsed instanceof ObjectNode root)) return body;
            OpenAiModelMappingRequestNormalizer.apply(root, account, OpenAiChatCompletionsBodyPolicy.FIELD_MODEL);
            normalizeOpenAIServiceTier(root, account);
            ensureStreamUsage(root);
            return JSON.writeValueAsString(root);
        } catch (OpenAiFastPolicyBlockedException e) {
            throw e;
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
        if (!root.path(OpenAiNormalizerProfile.FIELD_STREAM).asBoolean(false)) return;
        JsonNode streamOptionsNode = root.get(OpenAiNormalizerProfile.FIELD_STREAM_OPTIONS);
        ObjectNode streamOptions = streamOptionsNode instanceof ObjectNode objectNode
                ? objectNode
                : JSON.createObjectNode();
        streamOptions.put(OpenAiNormalizerProfile.FIELD_INCLUDE_USAGE, true);
        root.set(OpenAiNormalizerProfile.FIELD_STREAM_OPTIONS, streamOptions);
    }

    private void normalizeOpenAIServiceTier(ObjectNode root, AccountEntity account) {
        JsonNode value = root.get(OpenAiNormalizerProfile.FIELD_SERVICE_TIER);
        if (value == null || !value.isTextual()) {
            return;
        }
        String normalized = OpenAiNormalizerProfile.normalizeServiceTier(value.asText());
        if (normalized.isBlank()) {
            root.remove(OpenAiNormalizerProfile.FIELD_SERVICE_TIER);
            return;
        }
        String model = model(root);
        OpenAiFastPolicy.Decision decision = OpenAiFastPolicy.evaluate(
                fastPolicySettings(),
                account == null ? null : account.getType(),
                model,
                normalized);
        if (decision.blocks()) {
            String message = decision.message().isBlank()
                    ? OpenAiFastPolicy.defaultBlockMessage(normalized, model)
                    : decision.message();
            throw new OpenAiFastPolicyBlockedException(message, normalized, model);
        }
        if (decision.filters()) {
            root.remove(OpenAiNormalizerProfile.FIELD_SERVICE_TIER);
        } else {
            root.put(OpenAiNormalizerProfile.FIELD_SERVICE_TIER, normalized);
        }
    }

    private OpenAiFastPolicy.Settings fastPolicySettings() {
        return fastPolicyProvider == null ? OpenAiFastPolicy.defaultSettings() : fastPolicyProvider.current();
    }

    private static String model(ObjectNode root) {
        JsonNode model = root.get(OpenAiChatCompletionsBodyPolicy.FIELD_MODEL);
        return model != null && model.isTextual() ? model.asText() : "";
    }
}
