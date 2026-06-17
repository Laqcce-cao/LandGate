package com.landgate.types.gateway;

/**
 * Stable response-route predicates for protocol translation and stream
 * terminal validation.
 *
 * <p>This policy owns only client/upstream format decisions. It must not read
 * HTTP bodies, parse SSE lines, translate protocols, write servlet responses,
 * parse usage, or calculate billing.</p>
 */
public final class GatewayResponseRoutePolicy {

    private GatewayResponseRoutePolicy() {
    }

    public static boolean isPassthrough(String clientFormat, String upstreamFormat) {
        return !isBlank(clientFormat)
                && !isBlank(upstreamFormat)
                && clientFormat.equals(upstreamFormat);
    }

    public static boolean needsResponseTranslation(String clientFormat, String upstreamFormat) {
        return !isBlank(clientFormat)
                && !isBlank(upstreamFormat)
                && !clientFormat.equals(upstreamFormat);
    }

    public static boolean shouldNormalizeAnthropicUsageForTranslation(String upstreamFormat, String clientFormat) {
        return GatewayProtocolFormat.MESSAGES.is(upstreamFormat)
                && !GatewayProtocolFormat.MESSAGES.is(clientFormat);
    }

    public static boolean usesResponsesProtocolTerminal(String upstreamFormat, boolean translatedRoute) {
        return translatedRoute && GatewayProtocolFormat.RESPONSES.is(upstreamFormat);
    }

    public static boolean requiresProtocolTerminal(String upstreamFormat) {
        return GatewayProtocolFormat.MESSAGES.is(upstreamFormat)
                || GatewayProtocolFormat.RESPONSES.is(upstreamFormat)
                || GatewayProtocolFormat.CHAT_COMPLETIONS.is(upstreamFormat);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
