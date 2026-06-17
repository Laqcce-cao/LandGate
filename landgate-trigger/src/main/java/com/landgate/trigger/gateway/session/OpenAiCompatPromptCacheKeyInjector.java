package com.landgate.trigger.gateway.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.gateway.CompatPromptCacheKeyPolicy;
import com.landgate.types.gateway.GatewayRouteCompatibilityPolicy;
import com.landgate.types.gateway.OpenAiCompatModelPolicy;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;

/**
 * Route-aware OpenAI compat prompt_cache_key injection.
 *
 * <p>The derivation policy lives in types. This class only decides whether a
 * selected LandGate route is the Sub2API-compatible OpenAI OAuth Chat
 * Completions to Codex Responses path that needs automatic key injection.</p>
 */
public final class OpenAiCompatPromptCacheKeyInjector {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiCompatPromptCacheKeyInjector() {
    }

    public static Result injectChatCompletionsCodexCompat(AccountEntity account,
                                                          UpstreamRoute route,
                                                          String clientBody,
                                                          String responsesBody,
                                                          String requestedModel) {
        if (!isChatCompletionsCodexCompat(account, route)) {
            return Result.unchanged(responsesBody);
        }
        String existing = CompatPromptCacheKeyPolicy.extractPromptCacheKey(responsesBody);
        if (!existing.isBlank()) {
            return new Result(responsesBody, existing, false);
        }

        String model = OpenAiCompatModelPolicy.resolveOpenAiOAuthCodexModel(
                account.getPlatform(),
                account.getCredentials(),
                firstNonBlank(extractResponsesModel(responsesBody), requestedModel));
        if (!CompatPromptCacheKeyPolicy.shouldAutoInjectPromptCacheKeyForCompat(model)) {
            return Result.unchanged(responsesBody);
        }

        String promptCacheKey = CompatPromptCacheKeyPolicy.deriveChatCompletionsCompatPromptCacheKey(
                clientBody, model);
        if (promptCacheKey.isBlank()) {
            return Result.unchanged(responsesBody);
        }
        String injectedBody = CompatPromptCacheKeyPolicy.injectPromptCacheKey(responsesBody, promptCacheKey);
        return new Result(injectedBody, promptCacheKey, !injectedBody.equals(responsesBody));
    }

    private static boolean isChatCompletionsCodexCompat(AccountEntity account, UpstreamRoute route) {
        return account != null
                && route != null
                && GatewayRouteCompatibilityPolicy.isOpenAiOAuthChatCompletionsToCodexResponsesCompat(
                account.getPlatform(),
                account.getType(),
                route.upstreamPlatform(),
                route.normalizeCodexOAuthBody(),
                route.clientFormat(),
                route.upstreamFormat());
    }

    private static String extractResponsesModel(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode model = root.get(OpenAiResponsesBodyPolicy.FIELD_MODEL);
            return model != null && model.isTextual() ? model.asText().trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isBlank()) return value.trim();
        }
        return "";
    }

    public record Result(String body, String promptCacheKey, boolean injected) {
        static Result unchanged(String body) {
            return new Result(body, "", false);
        }
    }
}
