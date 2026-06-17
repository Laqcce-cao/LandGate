package com.landgate.trigger.gateway.route;

import com.landgate.types.gateway.GatewayProtocolFormat;
import com.landgate.types.gateway.GatewayResponsesRoutePolicy;

import java.net.URI;

/**
 * Route-level stream policy for upstream endpoint quirks.
 */
final class UpstreamStreamPolicy {

    private UpstreamStreamPolicy() {
    }

    /**
     * OpenAI Responses-family /compact returns regular JSON and must not be
     * handled as SSE, for both public Responses and ChatGPT Codex endpoints.
     */
    static boolean forceNonStreamingResponse(EndpointKind endpointKind, String targetUrl) {
        return (endpointKind == EndpointKind.OPENAI_CODEX_RESPONSES
                || endpointKind == EndpointKind.OPENAI_RESPONSES)
                && endpointPathEndsWith(targetUrl, GatewayResponsesRoutePolicy.COMPACT_SUBPATH);
    }

    /**
     * Public OpenAI Responses can be used as the upstream IR endpoint for
     * Anthropic Messages and Chat Completions clients. Those translated routes
     * use upstream SSE so the response bridge can preserve deltas/tool events
     * and then stream or aggregate according to the client request.
     */
    static boolean forceOpenAiApiKeyResponsesStreaming(String clientFormat, String upstreamFormat) {
        return GatewayProtocolFormat.RESPONSES.is(upstreamFormat)
                && (GatewayProtocolFormat.CHAT_COMPLETIONS.is(clientFormat)
                || GatewayProtocolFormat.MESSAGES.is(clientFormat));
    }

    /**
     * ChatGPT Codex Responses is SSE-only for the normal route; /compact is
     * the explicit JSON exception.
     */
    static boolean forceOpenAiOAuthCodexStreaming(EndpointKind endpointKind, String targetUrl) {
        return !forceNonStreamingResponse(endpointKind, targetUrl);
    }

    private static boolean endpointPathEndsWith(String targetUrl, String suffix) {
        if (targetUrl == null || targetUrl.isBlank() || suffix == null || suffix.isBlank()) {
            return false;
        }
        try {
            return stripTrailingSlashes(URI.create(targetUrl).getPath()).endsWith(suffix);
        } catch (Exception ignored) {
            return stripTrailingSlashes(targetUrl).endsWith(suffix);
        }
    }

    private static String stripTrailingSlashes(String value) {
        String out = value == null ? "" : value;
        while (out.endsWith("/") && out.length() > 1) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
