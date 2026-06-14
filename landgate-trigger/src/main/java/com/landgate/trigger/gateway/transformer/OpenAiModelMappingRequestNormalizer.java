package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.gateway.OpenAiModelMappingPolicy;

/**
 * Applies account-level OpenAI model_mapping to already parsed request bodies.
 *
 * <p>Policy lookup lives in landgate-types; this class only owns request JSON
 * mutation for OpenAI upstream normalizers.</p>
 */
final class OpenAiModelMappingRequestNormalizer {

    private OpenAiModelMappingRequestNormalizer() {
    }

    static boolean apply(ObjectNode root, AccountEntity account, String modelField) {
        if (root == null || account == null || modelField == null || modelField.isBlank()) {
            return false;
        }
        JsonNode model = root.get(modelField);
        if (model == null || !model.isTextual()) {
            return false;
        }
        OpenAiModelMappingPolicy.MappedModel mapping =
                OpenAiModelMappingPolicy.resolveMappedModel(
                        account.getPlatform(), account.getCredentials(), model.asText());
        if (!mapping.matched()) {
            return false;
        }
        root.put(modelField, mapping.model());
        return true;
    }
}
