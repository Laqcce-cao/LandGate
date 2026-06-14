package com.landgate.trigger.gateway.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;

import java.util.Optional;

/**
 * Reads route-related account options without making routing decisions.
 */
final class AccountRouteOptions {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AccountRouteOptions() {
    }

    static Optional<String> baseUrl(AccountEntity account) {
        if (account == null || account.getExtra() == null || account.getExtra().equals("{}")) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(account.getExtra());
            if (root.has("base_url")) {
                String baseUrl = root.get("base_url").asText();
                if (baseUrl != null && !baseUrl.isBlank()) {
                    return Optional.of(baseUrl);
                }
            }
        } catch (Exception ignored) {
            // Invalid account extra must not break routing; callers keep their default endpoint.
        }
        return Optional.empty();
    }
}
