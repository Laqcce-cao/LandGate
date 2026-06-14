package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.AnthropicModelMappingPolicy;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies account-level Anthropic model_mapping to request bodies.
 *
 * <p>Mapping lookup remains in {@link AnthropicModelMappingPolicy}; this class
 * owns only the JSON body mutation needed by Anthropic upstream request
 * builders.</p>
 */
@Slf4j
final class AnthropicModelMappingBodyNormalizer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AnthropicModelMappingBodyNormalizer() {
    }

    static String apply(AccountEntity account, String body) {
        if (account == null || body == null || body.isBlank()) {
            return body;
        }
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isObject() || !root.has(AnthropicMessagesBodyPolicy.FIELD_MODEL)) {
                return body;
            }
            String requestedModel = root.get(AnthropicMessagesBodyPolicy.FIELD_MODEL).asText("");
            AnthropicModelMappingPolicy.MappedModel mapped =
                    AnthropicModelMappingPolicy.resolveMappedModel(
                            account.getPlatform(), account.getType(), account.getExtra(), requestedModel);
            if (!mapped.matched() || mapped.model().equals(requestedModel)) {
                return body;
            }
            ((ObjectNode) root).put(AnthropicMessagesBodyPolicy.FIELD_MODEL, mapped.model());
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            log.debug("Failed to apply Anthropic model mapping: account_id={}",
                    account != null ? account.getId() : null);
            return body;
        }
    }
}
