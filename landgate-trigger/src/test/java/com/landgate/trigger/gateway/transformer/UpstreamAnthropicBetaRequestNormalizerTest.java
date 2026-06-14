package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.AnthropicApiProfile;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UpstreamAnthropicBetaRequestNormalizer tests")
class UpstreamAnthropicBetaRequestNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("Anthropic fast-mode beta maps OpenAI Responses compat body to priority service_tier")
    void fastModeBetaMapsOpenAiResponsesCompatBodyToPriorityServiceTier() throws Exception {
        String normalized = UpstreamAnthropicBetaRequestNormalizer.normalize(
                "{\"model\":\"gpt-5.5\",\"input\":\"Hi\"}",
                route(Platform.OPENAI, EndpointKind.OPENAI_RESPONSES, "messages", "responses"),
                Map.of(AnthropicApiProfile.HEADER_ANTHROPIC_BETA,
                        "foo, " + AnthropicClaudeCodeProfile.BETA_FAST_MODE));

        JsonNode root = JSON.readTree(normalized);

        assertEquals(OpenAiResponsesBodyPolicy.SERVICE_TIER_PRIORITY,
                root.get(OpenAiResponsesBodyPolicy.FIELD_SERVICE_TIER).asText());
    }

    @Test
    @DisplayName("non fast-mode beta leaves body unchanged")
    void nonFastModeBetaLeavesBodyUnchanged() {
        String body = "{\"model\":\"gpt-5.5\",\"input\":\"Hi\"}";

        String normalized = UpstreamAnthropicBetaRequestNormalizer.normalize(
                body,
                route(Platform.OPENAI, EndpointKind.OPENAI_RESPONSES, "messages", "responses"),
                Map.of(AnthropicApiProfile.HEADER_ANTHROPIC_BETA, "interleaved-thinking-2025-05-14"));

        assertEquals(body, normalized);
    }

    @Test
    @DisplayName("non OpenAI Responses compat route leaves body unchanged")
    void nonOpenAiResponsesCompatRouteLeavesBodyUnchanged() {
        String body = "{\"model\":\"gpt-5.5\",\"messages\":[]}";

        String normalized = UpstreamAnthropicBetaRequestNormalizer.normalize(
                body,
                route(Platform.OPENAI, EndpointKind.OPENAI_CHAT_COMPLETIONS, "messages", "chat_completions"),
                Map.of(AnthropicApiProfile.HEADER_ANTHROPIC_BETA, AnthropicClaudeCodeProfile.BETA_FAST_MODE));

        assertEquals(body, normalized);
    }

    @Test
    @DisplayName("Anthropic upstream route leaves body unchanged")
    void anthropicUpstreamRouteLeavesBodyUnchanged() {
        String body = "{\"model\":\"claude-sonnet-4-5\",\"messages\":[]}";

        String normalized = UpstreamAnthropicBetaRequestNormalizer.normalize(
                body,
                route(Platform.ANTHROPIC, EndpointKind.ANTHROPIC_MESSAGES, "messages", "messages"),
                Map.of(AnthropicApiProfile.HEADER_ANTHROPIC_BETA, AnthropicClaudeCodeProfile.BETA_FAST_MODE));

        assertEquals(body, normalized);
    }

    private static UpstreamRoute route(Platform platform,
                                       EndpointKind endpointKind,
                                       String clientFormat,
                                       String upstreamFormat) {
        return new UpstreamRoute(
                platform,
                clientFormat,
                upstreamFormat,
                endpointKind,
                "https://upstream.example.com",
                false,
                false,
                upstreamFormat,
                "test");
    }
}
