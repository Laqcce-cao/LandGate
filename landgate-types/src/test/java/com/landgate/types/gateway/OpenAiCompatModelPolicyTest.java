package com.landgate.types.gateway;

import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAI compat model policy tests")
class OpenAiCompatModelPolicyTest {

    @Test
    @DisplayName("uses account model_mapping target as Anthropic Messages compat model")
    void usesMappedOpenAiModel() {
        String credentials = """
                {"model_mapping":{"claude-sonnet-4-5":"gpt-5.3-codex"}}""";

        String model = OpenAiCompatModelPolicy.resolveAnthropicMessagesCompatModel(
                Platform.OPENAI, credentials, "claude-sonnet-4-5", "claude-sonnet-4-5", "");

        assertEquals("gpt-5.3-codex", model);
        assertTrue(OpenAiCompatModelPolicy.isAnthropicMessagesCodexCompat(
                Platform.OPENAI, credentials, "claude-sonnet-4-5", "claude-sonnet-4-5", ""));
    }

    @Test
    @DisplayName("falls back to requested Responses model when no model_mapping matches")
    void fallsBackToRequestedModel() {
        String model = OpenAiCompatModelPolicy.resolveAnthropicMessagesCompatModel(
                Platform.OPENAI, "{}", "", "gpt-5.4-high", "");

        assertEquals("gpt-5.4", model);
        assertTrue(OpenAiCompatModelPolicy.isAnthropicMessagesCodexCompat(
                Platform.OPENAI, "{}", "", "gpt-5.4-high", ""));
    }

    @Test
    @DisplayName("requested upstream model wins over Claude-shaped body model")
    void requestedUpstreamModelWinsOverClaudeBodyModel() {
        String model = OpenAiCompatModelPolicy.resolveAnthropicMessagesCompatModel(
                Platform.OPENAI, "{}", "claude-sonnet-4-5", "gpt-5.5", "claude-sonnet-4-5");

        assertEquals("gpt-5.5", model);
        assertTrue(OpenAiCompatModelPolicy.isAnthropicMessagesCodexCompat(
                Platform.OPENAI, "{}", "claude-sonnet-4-5", "gpt-5.5", "claude-sonnet-4-5"));
    }

    @Test
    @DisplayName("non Codex OpenAI model is not compat")
    void nonCodexModelIsNotCompat() {
        assertFalse(OpenAiCompatModelPolicy.isAnthropicMessagesCodexCompat(
                Platform.OPENAI, "{}", "gpt-4o", "gpt-4o", ""));
    }
}
