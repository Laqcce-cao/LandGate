package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.gateway.GatewayProtocolFormat;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;

/**
 * Synchronizes route-level upstream stream intent into upstream request bodies.
 *
 * <p>This class owns only stream-flag body normalization. It must not choose
 * routes, translate protocols, build auth headers, send HTTP requests, or
 * decide response handling.</p>
 */
public final class UpstreamStreamRequestNormalizer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private UpstreamStreamRequestNormalizer() {
    }

    public static String normalize(String body, UpstreamRoute route, boolean upstreamStream) {
        if (!upstreamStream || route == null || route.forceNonStreamingResponse()
                || !GatewayProtocolFormat.RESPONSES.is(route.upstreamFormat())) {
            return body;
        }
        if (body == null || body.isBlank()) return body;
        try {
            JsonNode parsed = JSON.readTree(body);
            if (!(parsed instanceof ObjectNode root)) return body;
            JsonNode stream = root.get(OpenAiResponsesBodyPolicy.FIELD_STREAM);
            if (stream != null && stream.isBoolean() && stream.asBoolean()) {
                return body;
            }
            root.put(OpenAiResponsesBodyPolicy.FIELD_STREAM, true);
            return JSON.writeValueAsString(root);
        } catch (Exception ignored) {
            return body;
        }
    }
}
