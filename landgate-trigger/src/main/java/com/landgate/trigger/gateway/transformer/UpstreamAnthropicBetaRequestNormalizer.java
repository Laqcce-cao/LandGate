package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.gateway.AnthropicApiProfile;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import com.landgate.types.gateway.GatewayHeaderPolicy;
import com.landgate.types.gateway.GatewayRouteCompatibilityPolicy;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;

import java.util.Map;

/**
 * Applies Anthropic Messages beta-header compatibility to OpenAI Responses
 * upstream request bodies.
 *
 * <p>This class owns only header-to-body compatibility normalization. It must
 * not choose routes/accounts, translate protocols, build auth headers, parse
 * responses, or calculate billing.</p>
 */
public final class UpstreamAnthropicBetaRequestNormalizer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private UpstreamAnthropicBetaRequestNormalizer() {
    }

    public static String normalize(String body, UpstreamRoute route, Map<String, String> requestHeaders) {
        if (!isOpenAiAnthropicMessagesCompat(route)
                || !AnthropicClaudeCodeProfile.containsBetaToken(
                GatewayHeaderPolicy.value(requestHeaders, AnthropicApiProfile.HEADER_ANTHROPIC_BETA),
                AnthropicClaudeCodeProfile.BETA_FAST_MODE)) {
            return body;
        }
        if (body == null || body.isBlank()) return body;
        try {
            JsonNode parsed = JSON.readTree(body);
            if (!(parsed instanceof ObjectNode root)) return body;
            root.put(OpenAiResponsesBodyPolicy.FIELD_SERVICE_TIER, OpenAiResponsesBodyPolicy.SERVICE_TIER_PRIORITY);
            return JSON.writeValueAsString(root);
        } catch (Exception ignored) {
            return body;
        }
    }

    private static boolean isOpenAiAnthropicMessagesCompat(UpstreamRoute route) {
        return route != null
                && GatewayRouteCompatibilityPolicy.isOpenAiAnthropicMessagesCompat(
                route.upstreamPlatform(), route.clientFormat(), route.upstreamFormat());
    }
}
