package com.landgate.types.gateway;

import com.landgate.types.enums.Platform;

/**
 * Model resolution policy for OpenAI Anthropic Messages compatibility.
 *
 * <p>Sub2API decides Codex compatibility from the resolved OpenAI upstream
 * model, not from the Claude-shaped client model. This type owns that pure
 * decision only; it does not mutate request JSON, select accounts, or build
 * upstream requests.</p>
 */
public final class OpenAiCompatModelPolicy {

    private OpenAiCompatModelPolicy() {
    }

    public static String resolveAnthropicMessagesCompatModel(Platform platform,
                                                             String credentialsJson,
                                                             String anthropicModel,
                                                             String requestedModel,
                                                             String responsesModel) {
        if (platform != Platform.OPENAI) {
            return firstNonBlank(requestedModel, responsesModel, anthropicModel);
        }

        for (String candidate : new String[]{anthropicModel, requestedModel, responsesModel}) {
            String model = trim(candidate);
            if (model.isBlank()) {
                continue;
            }
            OpenAiModelMappingPolicy.MappedModel mapped =
                    OpenAiModelMappingPolicy.resolveMappedModel(platform, credentialsJson, model);
            if (mapped.matched()) {
                return OpenAiCodexProfile.normalizeModel(mapped.model());
            }
        }

        String model = firstNonBlank(requestedModel, responsesModel, anthropicModel);
        return model.isBlank() ? "" : OpenAiCodexProfile.normalizeModel(model);
    }

    public static boolean isAnthropicMessagesCodexCompat(Platform platform,
                                                         String credentialsJson,
                                                         String anthropicModel,
                                                         String requestedModel,
                                                         String responsesModel) {
        return CompatPromptCacheKeyPolicy.shouldAutoInjectPromptCacheKeyForCompat(
                resolveAnthropicMessagesCompatModel(
                        platform, credentialsJson, anthropicModel, requestedModel, responsesModel));
    }

    public static String resolveOpenAiOAuthCodexModel(Platform platform,
                                                      String credentialsJson,
                                                      String model) {
        String candidate = trim(model);
        if (platform == Platform.OPENAI && !candidate.isBlank()) {
            OpenAiModelMappingPolicy.MappedModel mapped =
                    OpenAiModelMappingPolicy.resolveMappedModel(platform, credentialsJson, candidate);
            if (mapped.matched()) {
                candidate = mapped.model();
            }
        }
        return OpenAiCodexProfile.normalizeModel(candidate);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String trimmed = trim(value);
            if (!trimmed.isBlank()) return trimmed;
        }
        return "";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
