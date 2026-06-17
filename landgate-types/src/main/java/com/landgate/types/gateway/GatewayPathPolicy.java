package com.landgate.types.gateway;

import java.util.Arrays;

/**
 * Shared gateway path policy for security and servlet filters.
 * <p>
 * This class only describes which inbound paths belong to the gateway surface.
 * It must not decide protocol conversion, upstream routing, auth headers, or
 * body normalization.
 */
public final class GatewayPathPolicy {

    private static final String[] SECURITY_MATCHERS = {
            "/v1/**",
            "/v1beta/**",
            OpenAiEndpointPolicy.CHAT_COMPLETIONS_ALIAS_PATH,
            "/images/**",
            GatewayResponsesRoutePolicy.RESPONSES_ALIAS_PATH,
            "/responses/**",
            "/antigravity/**",
            "/backend-api/codex/**"
    };

    private static final String[] FILTER_PREFIXES = {
            "/v1/",
            "/v1beta/",
            OpenAiEndpointPolicy.CHAT_COMPLETIONS_ALIAS_PATH,
            "/images/",
            GatewayResponsesRoutePolicy.RESPONSES_ALIAS_PATH,
            "/antigravity/",
            "/backend-api/codex/"
    };

    private GatewayPathPolicy() {
    }

    public static String[] securityMatchers() {
        return Arrays.copyOf(SECURITY_MATCHERS, SECURITY_MATCHERS.length);
    }

    public static boolean isGatewayPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String prefix : FILTER_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
