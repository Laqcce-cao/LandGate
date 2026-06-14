package com.landgate.types.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;

import java.util.List;

/**
 * Account-level model support policy for gateway account selection.
 *
 * <p>Sub2API uses account {@code model_mapping} as the model support whitelist
 * when it is configured, and allows all models when no mapping exists. LandGate
 * also has an explicit {@code supportedModels} field; when present, that field
 * remains the stronger LandGate whitelist. This class owns only the support
 * decision and must not choose accounts, calculate load, or mutate requests.</p>
 */
public final class GatewayAccountModelSupportPolicy {

    public static final String WILDCARD_ALL = "*";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private GatewayAccountModelSupportPolicy() {
    }

    public static boolean supportsModel(Platform platform,
                                        AccountType accountType,
                                        String supportedModelsJson,
                                        String credentialsJson,
                                        String extraJson,
                                        String requestedModel) {
        String model = requestedModel == null ? "" : requestedModel.trim();
        if (model.isBlank()) {
            return true;
        }

        ModelWhitelist explicitWhitelist = parseSupportedModels(supportedModelsJson);
        if (explicitWhitelist.configured()) {
            return explicitWhitelist.supports(model);
        }

        if (OpenAiModelMappingPolicy.hasModelMapping(platform, credentialsJson)) {
            return OpenAiModelMappingPolicy.supportsRequestedModel(platform, credentialsJson, model);
        }
        if (AnthropicModelMappingPolicy.hasModelMapping(platform, accountType, extraJson)) {
            return AnthropicModelMappingPolicy.supportsRequestedModel(platform, accountType, extraJson, model);
        }

        return true;
    }

    static ModelWhitelist parseSupportedModels(String supportedModelsJson) {
        if (supportedModelsJson == null || supportedModelsJson.isBlank()) {
            return ModelWhitelist.unconfigured();
        }
        try {
            List<String> models = JSON.readValue(supportedModelsJson, STRING_LIST);
            if (models == null || models.isEmpty()) {
                return ModelWhitelist.unconfigured();
            }
            return new ModelWhitelist(true, models.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .toList());
        } catch (Exception ignored) {
            return new ModelWhitelist(true, List.of());
        }
    }

    record ModelWhitelist(boolean configured, List<String> models) {

        static ModelWhitelist unconfigured() {
            return new ModelWhitelist(false, List.of());
        }

        boolean supports(String model) {
            if (!configured || model == null || model.isBlank()) {
                return false;
            }
            if (models.contains(WILDCARD_ALL)) {
                return true;
            }
            return models.contains(model);
        }
    }
}
