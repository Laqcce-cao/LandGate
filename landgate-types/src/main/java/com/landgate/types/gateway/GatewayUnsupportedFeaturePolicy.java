package com.landgate.types.gateway;

/**
 * Stable client-facing response facts for gateway routes that are intentionally
 * outside the current three-protocol implementation scope.
 *
 * <p>This type may build protocol-shaped error bodies, but it must not write
 * servlet responses, choose routes, select accounts, perform auth, or translate
 * protocols.</p>
 */
public final class GatewayUnsupportedFeaturePolicy {

    public static final int STATUS_NOT_FOUND = 404;
    public static final String ANTHROPIC_TYPE_ERROR = "error";
    public static final String ERROR_TYPE_NOT_IMPLEMENTED = "not_implemented";
    public static final String ERROR_TYPE_NOT_FOUND = "not_found_error";
    public static final String ERROR_TYPE_INVALID_REQUEST = "invalid_request_error";
    public static final String ERROR_TYPE_UPSTREAM = "upstream_error";
    public static final String ERROR_TYPE_API = "api_error";
    public static final String ERROR_TYPE_UNSUPPORTED = "unsupported_error";
    public static final String GOOGLE_STATUS_UNSUPPORTED = "UNSUPPORTED";
    public static final String COUNT_TOKENS_NOT_IMPLEMENTED_MESSAGE = "count_tokens is not yet implemented";
    public static final String COUNT_TOKENS_UNSUPPORTED_PLATFORM_MESSAGE =
            "Token counting is not supported for this platform";
    public static final String COUNT_TOKENS_UNSUPPORTED_UPSTREAM_MESSAGE =
            "count_tokens endpoint is not supported by upstream";
    public static final String GEMINI_UNSUPPORTED_MESSAGE = "Gemini gateway is not supported in this build";
    public static final String ANTIGRAVITY_UNSUPPORTED_MESSAGE = "Antigravity gateway is not supported in this build";

    private GatewayUnsupportedFeaturePolicy() {
    }

    public static String countTokensNotImplementedBody() {
        return "{\"type\":\"" + ANTHROPIC_TYPE_ERROR
                + "\",\"error\":{\"type\":\"" + ERROR_TYPE_NOT_IMPLEMENTED
                + "\",\"message\":\"" + COUNT_TOKENS_NOT_IMPLEMENTED_MESSAGE + "\"}}";
    }

    public static String openAiUnsupportedBody(String message) {
        return "{\"error\":{\"message\":\"" + escapeJson(message)
                + "\",\"type\":\"" + ERROR_TYPE_UNSUPPORTED + "\"}}";
    }

    public static String googleUnsupportedBody() {
        return googleErrorBody(STATUS_NOT_FOUND, GOOGLE_STATUS_UNSUPPORTED, GEMINI_UNSUPPORTED_MESSAGE);
    }

    public static String googleErrorBody(int status, String code, String message) {
        return "{\"error\":{\"code\":" + status
                + ",\"message\":\"" + escapeJson(message)
                + "\",\"status\":\"" + escapeJson(code) + "\"}}";
    }

    public static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
