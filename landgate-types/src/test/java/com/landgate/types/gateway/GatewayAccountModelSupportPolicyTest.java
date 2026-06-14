package com.landgate.types.gateway;

import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GatewayAccountModelSupportPolicy")
class GatewayAccountModelSupportPolicyTest {

    @Test
    @DisplayName("explicit supportedModels remains the strongest whitelist")
    void explicitSupportedModelsRemainsStrongestWhitelist() {
        assertTrue(GatewayAccountModelSupportPolicy.supportsModel(
                Platform.OPENAI,
                AccountType.API_KEY,
                "[\"gpt-5.4\"]",
                "{\"model_mapping\":{\"gpt-*\":\"gpt-5.4\"}}",
                null,
                "gpt-5.4"));

        assertFalse(GatewayAccountModelSupportPolicy.supportsModel(
                Platform.OPENAI,
                AccountType.API_KEY,
                "[\"gpt-5.4\"]",
                "{\"model_mapping\":{\"gpt-*\":\"gpt-5.4\"}}",
                null,
                "gpt-5.3"));
    }

    @Test
    @DisplayName("OpenAI model_mapping acts as support whitelist when supportedModels is not configured")
    void openAiModelMappingSupportsSelectionWhenSupportedModelsMissing() {
        assertTrue(GatewayAccountModelSupportPolicy.supportsModel(
                Platform.OPENAI,
                AccountType.API_KEY,
                null,
                "{\"model_mapping\":{\"gpt-5.*\":\"gpt-5.4\"}}",
                null,
                "gpt-5.3"));

        assertFalse(GatewayAccountModelSupportPolicy.supportsModel(
                Platform.OPENAI,
                AccountType.API_KEY,
                null,
                "{\"model_mapping\":{\"gpt-5.*\":\"gpt-5.4\"}}",
                null,
                "o3-mini"));
    }

    @Test
    @DisplayName("Anthropic model_mapping acts as support whitelist when supportedModels is not configured")
    void anthropicModelMappingSupportsSelectionWhenSupportedModelsMissing() {
        assertTrue(GatewayAccountModelSupportPolicy.supportsModel(
                Platform.ANTHROPIC,
                AccountType.API_KEY,
                "[]",
                null,
                "{\"model_mapping\":{\"claude-*\":\"claude-sonnet-4-5-20241022\"}}",
                "claude-sonnet-4-20250514"));

        assertFalse(GatewayAccountModelSupportPolicy.supportsModel(
                Platform.ANTHROPIC,
                AccountType.API_KEY,
                "[]",
                null,
                "{\"model_mapping\":{\"claude-opus-*\":\"claude-opus-4-5\"}}",
                "claude-sonnet-4-20250514"));
    }

    @Test
    @DisplayName("missing supportedModels and missing mapping allow all models like Sub2API")
    void missingSupportedModelsAndMappingAllowAllModels() {
        assertTrue(GatewayAccountModelSupportPolicy.supportsModel(
                Platform.OPENAI,
                AccountType.API_KEY,
                null,
                "{}",
                null,
                "gpt-unknown"));

        assertTrue(GatewayAccountModelSupportPolicy.supportsModel(
                Platform.ANTHROPIC,
                AccountType.API_KEY,
                "",
                null,
                "{}",
                "claude-unknown"));
    }
}
