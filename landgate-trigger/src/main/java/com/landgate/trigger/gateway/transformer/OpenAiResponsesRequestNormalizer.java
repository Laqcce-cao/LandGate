package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.gateway.GatewayResponsesRoutePolicy;
import com.landgate.types.gateway.OpenAiCompactAccountPolicy;
import com.landgate.types.gateway.OpenAiCompactRequestBodyPolicy;
import com.landgate.types.gateway.OpenAiFastPolicy;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes public OpenAI Responses API-key requests before forwarding upstream.
 */
@Slf4j
@Component
public class OpenAiResponsesRequestNormalizer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final OpenAiFastPolicyProvider fastPolicyProvider;

    public OpenAiResponsesRequestNormalizer() {
        this(null);
    }

    @Autowired
    OpenAiResponsesRequestNormalizer(OpenAiFastPolicyProvider fastPolicyProvider) {
        this.fastPolicyProvider = fastPolicyProvider;
    }

    public String normalize(String body) {
        return normalize(body, null);
    }

    public String normalize(String body, UpstreamRoute route) {
        return normalize(body, route, null);
    }

    public String normalize(String body, UpstreamRoute route, AccountEntity account) {
        try {
            JsonNode parsed = JSON.readTree(body);
            if (!(parsed instanceof ObjectNode root)) return body;
            if (isCompactEndpoint(route)) {
                applyCompactModelMapping(root, account);
                retainCompactRequestFields(root);
            } else {
                OpenAiModelMappingRequestNormalizer.apply(root, account, OpenAiResponsesBodyPolicy.FIELD_MODEL);
                normalizeOpenAIServiceTier(root, account);
                normalizeReasoningEffort(root);
                root.remove(OpenAiNormalizerProfile.publicResponsesUnsupportedFields());
            }
            sanitizeEmptyBase64InputImages(root);
            return JSON.writeValueAsString(root);
        } catch (OpenAiFastPolicyBlockedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to normalize OpenAI API key Responses request body", e);
            return body;
        }
    }

    private static void applyCompactModelMapping(ObjectNode root, AccountEntity account) {
        if (account == null) {
            return;
        }
        JsonNode model = root.get(OpenAiResponsesBodyPolicy.FIELD_MODEL);
        if (model == null || !model.isTextual()) {
            return;
        }
        OpenAiCompactAccountPolicy.CompactModelMapping mapping =
                OpenAiCompactAccountPolicy.resolveCompactMappedModel(
                        account.getCredentials(), model.asText());
        if (mapping.matched()) {
            root.put(OpenAiResponsesBodyPolicy.FIELD_MODEL, mapping.model());
        }
    }

    private static boolean isCompactEndpoint(UpstreamRoute route) {
        return route != null && GatewayResponsesRoutePolicy.isCompactPath(route.targetUrl());
    }

    private static void retainCompactRequestFields(ObjectNode root) {
        List<String> toRemove = new ArrayList<>();
        root.fieldNames().forEachRemaining(field -> {
            if (!OpenAiCompactRequestBodyPolicy.isCompactAllowedField(field)) {
                toRemove.add(field);
            }
        });
        root.remove(toRemove);
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
        OpenAiFastPolicy.Decision decision = OpenAiFastPolicy.evaluate(
                fastPolicySettings(),
                account == null ? null : account.getType(),
                model(root),
                normalized);
        if (decision.blocks()) {
            String message = decision.message().isBlank()
                    ? OpenAiFastPolicy.defaultBlockMessage(normalized, model(root))
                    : decision.message();
            throw new OpenAiFastPolicyBlockedException(message, normalized, model(root));
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
        JsonNode model = root.get(OpenAiResponsesBodyPolicy.FIELD_MODEL);
        return model != null && model.isTextual() ? model.asText() : "";
    }

    private static void normalizeReasoningEffort(ObjectNode root) {
        JsonNode reasoning = root.get(OpenAiResponsesBodyPolicy.FIELD_REASONING);
        if (!(reasoning instanceof ObjectNode reasoningObject)) {
            return;
        }
        JsonNode effort = reasoningObject.get(OpenAiResponsesBodyPolicy.FIELD_EFFORT);
        if (effort == null || !effort.isTextual()) {
            return;
        }
        String normalized = OpenAiResponsesBodyPolicy.normalizeReasoningEffort(effort.asText());
        if (!effort.asText().equals(normalized)) {
            reasoningObject.put(OpenAiResponsesBodyPolicy.FIELD_EFFORT, normalized);
        }
    }

    private static void sanitizeEmptyBase64InputImages(ObjectNode root) {
        JsonNode input = root.get(OpenAiNormalizerProfile.FIELD_INPUT);
        if (input == null || !input.isArray()) {
            return;
        }
        var normalizedItems = JSON.createArrayNode();
        boolean changed = false;
        for (JsonNode item : input) {
            if (!(item instanceof ObjectNode itemObject)) {
                normalizedItems.add(item);
                continue;
            }
            if (shouldDropEmptyBase64InputImagePart(itemObject)) {
                changed = true;
                continue;
            }
            JsonNode content = itemObject.get(OpenAiNormalizerProfile.FIELD_CONTENT);
            if (content == null || !content.isArray()) {
                normalizedItems.add(item);
                continue;
            }
            var normalizedParts = JSON.createArrayNode();
            boolean itemChanged = false;
            for (JsonNode part : content) {
                if (part instanceof ObjectNode partObject && shouldDropEmptyBase64InputImagePart(partObject)) {
                    changed = true;
                    itemChanged = true;
                    continue;
                }
                normalizedParts.add(part);
            }
            if (itemChanged) {
                if (normalizedParts.isEmpty()) {
                    continue;
                }
                ObjectNode copied = itemObject.deepCopy();
                copied.set(OpenAiNormalizerProfile.FIELD_CONTENT, normalizedParts);
                normalizedItems.add(copied);
            } else {
                normalizedItems.add(item);
            }
        }
        if (changed) {
            root.set(OpenAiNormalizerProfile.FIELD_INPUT, normalizedItems);
        }
    }

    private static boolean shouldDropEmptyBase64InputImagePart(ObjectNode part) {
        JsonNode type = part.get(OpenAiNormalizerProfile.FIELD_TYPE);
        JsonNode imageUrl = part.get(OpenAiNormalizerProfile.FIELD_IMAGE_URL);
        return type != null && type.isTextual()
                && OpenAiNormalizerProfile.TYPE_INPUT_IMAGE.equals(type.asText().trim())
                && imageUrl != null && imageUrl.isTextual()
                && OpenAiNormalizerProfile.isEmptyBase64DataUri(imageUrl.asText().trim());
    }
}
