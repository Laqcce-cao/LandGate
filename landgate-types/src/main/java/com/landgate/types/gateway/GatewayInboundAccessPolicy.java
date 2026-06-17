package com.landgate.types.gateway;

/**
 * Stable client-facing facts for LandGate inbound API-key access checks.
 *
 * <p>This policy owns only status codes, error codes, and message formatting
 * for inbound gateway access failures. It must not read servlet requests,
 * query repositories, check balances, select accounts, or build upstream auth.</p>
 */
public final class GatewayInboundAccessPolicy {

    public static final int STATUS_UNAUTHORIZED = 401;
    public static final int STATUS_FORBIDDEN = 403;
    public static final int STATUS_PAYMENT_REQUIRED = 402;
    public static final int STATUS_TOO_MANY_REQUESTS = 429;

    public static final String CODE_AUTHENTICATION = "authentication_error";
    public static final String CODE_PERMISSION = ErrorResponsePolicy.ERROR_CODE_PERMISSION;
    public static final String CODE_QUOTA_EXCEEDED = "quota_exceeded";
    public static final String CODE_INSUFFICIENT_BALANCE = "insufficient_balance";

    public static final String MESSAGE_MISSING_API_KEY = "Missing API key";
    public static final String MESSAGE_GROUP_NOT_ASSIGNED =
            "API key has no group assigned. Contact admin to assign a group.";
    public static final String MESSAGE_USER_NOT_FOUND = "User not found";
    public static final String MESSAGE_INSUFFICIENT_BALANCE =
            "Insufficient balance. Please recharge your account.";

    private GatewayInboundAccessPolicy() {
    }

    public static String disabledGroupMessage(String groupName) {
        return "Group '" + safe(groupName) + "' is disabled.";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
