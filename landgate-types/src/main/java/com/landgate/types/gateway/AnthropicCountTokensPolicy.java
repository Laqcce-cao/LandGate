package com.landgate.types.gateway;

import java.util.Locale;

/**
 * Stable client/upstream facts for Anthropic Messages count_tokens compatibility.
 *
 * <p>This policy owns endpoint path/query and protocol-shaped error semantics
 * only. It must not select accounts, build servlet responses, send HTTP
 * requests, or perform billing.</p>
 */
public final class AnthropicCountTokensPolicy {

    public static final String CLIENT_PATH = "/v1/messages/count_tokens";
    public static final String UPSTREAM_PATH = "/v1/messages/count_tokens";
    public static final String UPSTREAM_QUERY = "beta=true";
    public static final String UPSTREAM_PATH_WITH_QUERY = UPSTREAM_PATH + "?" + UPSTREAM_QUERY;

    public static final int STATUS_BAD_REQUEST = 400;
    public static final int STATUS_NOT_FOUND = 404;
    public static final int STATUS_BAD_GATEWAY = 502;
    public static final int STATUS_SERVICE_UNAVAILABLE = 503;
    public static final int STATUS_INTERNAL_SERVER_ERROR = 500;

    public static final String MESSAGE_EMPTY_BODY = "Request body is empty";
    public static final String MESSAGE_PARSE_BODY_FAILED = "Failed to parse request body";
    public static final String MESSAGE_MODEL_REQUIRED = "model is required";
    public static final String MESSAGE_NO_AVAILABLE_ACCOUNT = "Service temporarily unavailable";
    public static final String MESSAGE_GET_ACCESS_TOKEN_FAILED = "Failed to get access token";
    public static final String MESSAGE_BUILD_REQUEST_FAILED = "Failed to build request";
    public static final String MESSAGE_REQUEST_FAILED = "Request failed";
    public static final String MESSAGE_READ_RESPONSE_FAILED = "Failed to read response";
    public static final String MESSAGE_UPSTREAM_REQUEST_FAILED = "Upstream request failed";
    public static final String MESSAGE_RATE_LIMIT_EXCEEDED = "Rate limit exceeded";
    public static final String MESSAGE_SERVICE_OVERLOADED = "Service overloaded";

    private AnthropicCountTokensPolicy() {
    }

    public static String errorBody(String errorType, String message) {
        return "{\"type\":\"" + GatewayUnsupportedFeaturePolicy.ANTHROPIC_TYPE_ERROR
                + "\",\"error\":{\"type\":\"" + GatewayUnsupportedFeaturePolicy.escapeJson(errorType)
                + "\",\"message\":\"" + GatewayUnsupportedFeaturePolicy.escapeJson(message) + "\"}}";
    }

    public static String upstreamErrorMessage(int statusCode) {
        return switch (statusCode) {
            case 429 -> MESSAGE_RATE_LIMIT_EXCEEDED;
            case 529 -> MESSAGE_SERVICE_OVERLOADED;
            default -> MESSAGE_UPSTREAM_REQUEST_FAILED;
        };
    }

    public static boolean isUnsupportedUpstream404(int statusCode, String body) {
        if (statusCode != STATUS_NOT_FOUND || body == null || body.isBlank()) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains(UPSTREAM_PATH)
                || (normalized.contains("count_tokens") && normalized.contains("not found"));
    }
}
