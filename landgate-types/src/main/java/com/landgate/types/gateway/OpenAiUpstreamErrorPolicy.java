package com.landgate.types.gateway;

/**
 * Sub2API-compatible OpenAI upstream error failover facts.
 *
 * <p>This policy owns only pure OpenAI upstream error classification. It must
 * not select accounts, mutate requests, write responses, or mark account
 * health.</p>
 */
public final class OpenAiUpstreamErrorPolicy {

    public static final int STATUS_BAD_REQUEST = 400;
    public static final int STATUS_UNAUTHORIZED = 401;
    public static final int STATUS_PAYMENT_REQUIRED = 402;
    public static final int STATUS_FORBIDDEN = 403;
    public static final int STATUS_TOO_MANY_REQUESTS = 429;
    public static final int STATUS_OVERLOADED = 529;
    public static final int STATUS_SERVER_ERROR_MIN = 500;
    public static final long FORBIDDEN_TEMP_UNSCHEDULABLE_SECONDS = 600;
    public static final long OAUTH_UNAUTHORIZED_TEMP_UNSCHEDULABLE_SECONDS = 600;
    public static final int FORBIDDEN_DISABLE_THRESHOLD = 3;
    public static final int FORBIDDEN_COUNTER_WINDOW_MINUTES = 180;
    public static final String ERROR_CODE_TOKEN_INVALIDATED = "token_invalidated";
    public static final String ERROR_CODE_TOKEN_REVOKED = "token_revoked";
    public static final String DETAIL_UNAUTHORIZED = "Unauthorized";
    public static final String DETAIL_CODE_DEACTIVATED_WORKSPACE = "deactivated_workspace";

    private OpenAiUpstreamErrorPolicy() {
    }

    public static boolean isFailoverStatus(int statusCode) {
        return switch (statusCode) {
            case STATUS_UNAUTHORIZED,
                    STATUS_PAYMENT_REQUIRED,
                    STATUS_FORBIDDEN,
                    STATUS_TOO_MANY_REQUESTS,
                    STATUS_OVERLOADED -> true;
            default -> statusCode >= STATUS_SERVER_ERROR_MIN;
        };
    }

    public static boolean shouldFailover(int statusCode, String upstreamMessage, String upstreamBody) {
        return isFailoverStatus(statusCode)
                || OpenAiTransientProcessingErrorPolicy.isTransientProcessingError(
                statusCode, upstreamMessage, upstreamBody);
    }

    public static boolean isPaymentRequired(int statusCode) {
        return statusCode == STATUS_PAYMENT_REQUIRED;
    }

    public static boolean isForbidden(int statusCode) {
        return statusCode == STATUS_FORBIDDEN;
    }

    public static boolean isPermanentUnauthorized(int statusCode, String upstreamBody) {
        if (statusCode != STATUS_UNAUTHORIZED) {
            return false;
        }
        String code = ErrorResponsePolicy.extractUpstreamErrorCode(upstreamBody);
        return ERROR_CODE_TOKEN_INVALIDATED.equals(code)
                || ERROR_CODE_TOKEN_REVOKED.equals(code)
                || DETAIL_UNAUTHORIZED.equals(ErrorResponsePolicy.extractTopLevelDetail(upstreamBody));
    }

    public static String permanentUnauthorizedAccountError(String upstreamBody, String upstreamMessage) {
        String message = upstreamMessage == null ? "" : upstreamMessage.trim();
        String code = ErrorResponsePolicy.extractUpstreamErrorCode(upstreamBody);
        if (ERROR_CODE_TOKEN_INVALIDATED.equals(code) || ERROR_CODE_TOKEN_REVOKED.equals(code)) {
            return message.isEmpty()
                    ? "Token revoked (401): account authentication permanently revoked"
                    : "Token revoked (401): " + message;
        }
        if (DETAIL_UNAUTHORIZED.equals(ErrorResponsePolicy.extractTopLevelDetail(upstreamBody))) {
            return message.isEmpty()
                    ? "Unauthorized (401): account authentication failed permanently"
                    : "Unauthorized (401): " + message;
        }
        return "";
    }

    public static String oauthUnauthorizedTempUnschedulableReason(String upstreamMessage) {
        String message = upstreamMessage == null ? "" : upstreamMessage.trim();
        return message.isEmpty()
                ? "Authentication failed (401): invalid or expired credentials"
                : "OAuth 401: " + message;
    }

    public static String paymentRequiredAccountError(String upstreamMessage) {
        return paymentRequiredAccountError("", upstreamMessage);
    }

    public static String paymentRequiredAccountError(String upstreamBody, String upstreamMessage) {
        if (DETAIL_CODE_DEACTIVATED_WORKSPACE.equals(ErrorResponsePolicy.extractDetailCode(upstreamBody))) {
            return "Workspace deactivated (402): workspace has been deactivated";
        }
        String message = upstreamMessage == null ? "" : upstreamMessage.trim();
        return message.isEmpty()
                ? "Payment required (402): insufficient balance or billing issue"
                : "Payment required (402): " + message;
    }

    public static String forbiddenTempUnschedulableReason(String upstreamMessage) {
        return forbiddenTempUnschedulableReason(0, upstreamMessage);
    }

    public static String forbiddenTempUnschedulableReason(long count, String upstreamMessage) {
        String prefix = count > 0
                ? "OpenAI 403 temporary cooldown (" + count + "/" + FORBIDDEN_DISABLE_THRESHOLD + "): "
                : "OpenAI 403 temporary cooldown: ";
        return prefix + forbiddenAccountError(upstreamMessage);
    }

    public static String forbiddenAccountError(String upstreamMessage) {
        String message = upstreamMessage == null ? "" : upstreamMessage.trim();
        if (message.isEmpty()) {
            message = "account may be suspended or lack permissions";
        }
        return "Access forbidden (403): " + message;
    }

    public static String forbiddenThresholdAccountError(String upstreamMessage, long count) {
        return forbiddenAccountError(upstreamMessage)
                + " | consecutive_403=" + count + "/" + FORBIDDEN_DISABLE_THRESHOLD;
    }

}
