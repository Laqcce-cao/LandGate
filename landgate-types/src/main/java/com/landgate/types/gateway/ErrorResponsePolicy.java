package com.landgate.types.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

/**
 * Gateway-visible upstream error policy.
 *
 * <p>The handler owns failover flow; this policy owns stable protocol-facing
 * facts: retryable upstream error signatures, safe client messages, and
 * protocol error type mapping.</p>
 */
public final class ErrorResponsePolicy {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<RetryRule> RETRY_RULES = List.of(
            new RetryRule(Set.of(400, 404),
                    Set.of("model_not_found", "model not found", "deprecated", "unknown model")),
            new RetryRule(Set.of(403),
                    Set.of("unsupported_country", "unsupported_region", "region_not_supported",
                            "not available in your country", "not available in your region")),
            new RetryRule(Set.of(400, 404),
                    Set.of("invalid_model", "no such model", "model is not supported"))
    );

    private static final List<JsonMessagePath> SAFE_MESSAGE_PATHS = List.of(
            new JsonMessagePath("\"message\":\""),
            new JsonMessagePath("\"error\":{\"message\":\"")
    );

    private static final Set<Integer> FORCE_DEFAULT_MESSAGE_STATUSES = Set.of(402, 429, 503);
    private static final int MAX_SAFE_MESSAGE_LENGTH = 200;

    private ErrorResponsePolicy() {
    }

    public static List<RetryRule> retryRules() {
        return RETRY_RULES;
    }

    public static String errorCodeForStatus(int statusCode) {
        return switch (statusCode) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 402 -> "insufficient_quota";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 422 -> "unprocessable_error";
            case 429 -> "rate_limit_error";
            case 529 -> "overloaded_error";
            default -> "upstream_error";
        };
    }

    public static String safeMessageForStatus(int statusCode, String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return defaultMessage(statusCode);
        }
        if (FORCE_DEFAULT_MESSAGE_STATUSES.contains(statusCode)) {
            return defaultMessage(statusCode);
        }

        String parsed = extractJsonMessage(responseBody);
        if (!parsed.isBlank()) {
            return truncateSafeMessage(parsed);
        }

        for (JsonMessagePath path : SAFE_MESSAGE_PATHS) {
            String message = extractJsonStringByLiteralPath(responseBody, path.literal());
            if (!message.isBlank()) {
                return truncateSafeMessage(message);
            }
        }

        return defaultMessage(statusCode);
    }

    public static String extractUpstreamErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return "";
        }
        try {
            return extractUpstreamErrorMessage(JSON.readTree(responseBody));
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String extractUpstreamErrorMessage(JsonNode root) {
        if (root == null) {
            return "";
        }
        String responseError = textAt(root, "response", "error", "message");
        if (!responseError.isBlank()) {
            return responseError;
        }
        String message = textAt(root, "error", "message");
        if (!message.isBlank()) {
            String inner = extractInnerJsonErrorMessage(message);
            return inner.isBlank() ? message : inner;
        }
        String detail = textAt(root, "detail");
        if (!detail.isBlank()) {
            return detail;
        }
        return textAt(root, "message");
    }

    public static String extractUpstreamErrorCode(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return "";
        }
        try {
            return extractUpstreamErrorCode(JSON.readTree(responseBody));
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String extractUpstreamErrorCode(JsonNode root) {
        if (root == null) {
            return "";
        }
        String code = textAt(root, "error", "code");
        if (!code.isBlank()) {
            return code;
        }
        String message = textAt(root, "error", "message");
        if (message.isBlank() || !message.trim().startsWith("{")) {
            return "";
        }
        try {
            return textAt(JSON.readTree(message), "error", "code");
        } catch (Exception ignored) {
            int lastBrace = message.lastIndexOf('}');
            if (lastBrace < 0) {
                return "";
            }
            try {
                return textAt(JSON.readTree(message.substring(0, lastBrace + 1)), "error", "code");
            } catch (Exception ignoredAgain) {
                return "";
            }
        }
    }

    public static String extractTopLevelDetail(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return "";
        }
        try {
            return textAt(JSON.readTree(responseBody), "detail");
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String extractDetailCode(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return "";
        }
        try {
            return textAt(JSON.readTree(responseBody), "detail", "code");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String extractJsonMessage(String responseBody) {
        try {
            return extractUpstreamErrorMessage(JSON.readTree(responseBody));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String extractInnerJsonErrorMessage(String message) {
        String trimmed = message == null ? "" : message.trim();
        if (!trimmed.startsWith("{")) {
            return "";
        }
        try {
            JsonNode inner = JSON.readTree(trimmed);
            return textAt(inner, "error", "message");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String textAt(JsonNode root, String... path) {
        JsonNode current = root;
        for (String part : path) {
            if (current == null || !current.isObject()) {
                return "";
            }
            current = current.get(part);
        }
        return current != null && current.isTextual() ? current.asText().trim() : "";
    }

    public static String defaultMessage(int statusCode) {
        return switch (statusCode) {
            case 400 -> "Invalid request. Please check your parameters.";
            case 401 -> "Authentication failed. Please check your credentials.";
            case 402 -> "The service is temporarily unavailable. Please try again later.";
            case 403 -> "Access denied. Your account may not have permission.";
            case 404 -> "The requested resource was not found.";
            case 422 -> "The request could not be processed.";
            case 429 -> "Too many requests. Please slow down.";
            case 503 -> "The service is temporarily unavailable. Please try again later.";
            default -> "An upstream error occurred. Status: " + statusCode;
        };
    }

    private static String extractJsonStringByLiteralPath(String responseBody, String path) {
        int idx = responseBody.indexOf(path);
        if (idx < 0) {
            return "";
        }
        int start = idx + path.length();
        int end = responseBody.indexOf('"', start);
        if (end <= start) {
            return "";
        }
        return responseBody.substring(start, end);
    }

    private static String truncateSafeMessage(String message) {
        if (message.length() <= MAX_SAFE_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_SAFE_MESSAGE_LENGTH) + "...";
    }

    public record RetryRule(Set<Integer> codes, Set<String> keywords) {
    }

    private record JsonMessagePath(String literal) {
    }
}
