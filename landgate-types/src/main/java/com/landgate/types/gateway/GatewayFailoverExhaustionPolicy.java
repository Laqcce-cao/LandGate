package com.landgate.types.gateway;

import com.landgate.types.enums.Platform;

/**
 * Sub2API-compatible client-visible error mapping when all failover candidates
 * are exhausted after upstream errors.
 *
 * <p>This policy owns only stable mapping facts. It does not select accounts,
 * mark health, parse bodies, or write HTTP responses.</p>
 */
public final class GatewayFailoverExhaustionPolicy {

    public static final String CODE_UPSTREAM_ERROR = "upstream_error";
    public static final String CODE_API_ERROR = "api_error";
    public static final String CODE_RATE_LIMIT_ERROR = "rate_limit_error";
    public static final String CODE_OVERLOADED_ERROR = "overloaded_error";
    public static final String CODE_SERVER_ERROR = "server_error";

    public static final String MESSAGE_UPSTREAM_AUTH_FAILED =
            "Upstream authentication failed, please contact administrator";
    public static final String MESSAGE_UPSTREAM_ACCESS_FORBIDDEN =
            "Upstream access forbidden, please contact administrator";
    public static final String MESSAGE_UPSTREAM_RATE_LIMIT =
            "Upstream rate limit exceeded, please retry later";
    public static final String MESSAGE_UPSTREAM_OVERLOADED =
            "Upstream service overloaded, please retry later";
    public static final String MESSAGE_UPSTREAM_TEMPORARILY_UNAVAILABLE =
            "Upstream service temporarily unavailable";
    public static final String MESSAGE_UPSTREAM_REQUEST_FAILED =
            "Upstream request failed";
    public static final String MESSAGE_ALL_ACCOUNTS_EXHAUSTED =
            "All available accounts exhausted";

    private static final int HTTP_BAD_GATEWAY = 502;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private GatewayFailoverExhaustionPolicy() {
    }

    public static Decision decide(String clientFormat, Platform upstreamPlatform, int upstreamStatusCode) {
        String normalizedFormat = GatewayProtocolFormat.normalizeId(clientFormat);
        if (GatewayProtocolFormat.RESPONSES.is(normalizedFormat)
                || GatewayProtocolFormat.CHAT_COMPLETIONS.is(normalizedFormat)) {
            if (upstreamPlatform == Platform.ANTHROPIC) {
                return new Decision(nonZeroStatus(upstreamStatusCode), CODE_SERVER_ERROR, MESSAGE_ALL_ACCOUNTS_EXHAUSTED);
            }
            return mapOpenAiGatewayStyle(upstreamStatusCode);
        }

        if (upstreamPlatform == Platform.OPENAI) {
            return mapOpenAiGatewayStyle(upstreamStatusCode);
        }
        return mapAnthropicGatewayStyle(upstreamStatusCode);
    }

    private static Decision mapAnthropicGatewayStyle(int upstreamStatusCode) {
        return switch (upstreamStatusCode) {
            case 401 -> new Decision(HTTP_BAD_GATEWAY, CODE_UPSTREAM_ERROR, MESSAGE_UPSTREAM_AUTH_FAILED);
            case 403 -> new Decision(HTTP_BAD_GATEWAY, CODE_UPSTREAM_ERROR, MESSAGE_UPSTREAM_ACCESS_FORBIDDEN);
            case 429 -> new Decision(HTTP_TOO_MANY_REQUESTS, CODE_RATE_LIMIT_ERROR, MESSAGE_UPSTREAM_RATE_LIMIT);
            case 529 -> new Decision(HTTP_SERVICE_UNAVAILABLE, CODE_OVERLOADED_ERROR, MESSAGE_UPSTREAM_OVERLOADED);
            case 500, 502, 503, 504 ->
                    new Decision(HTTP_BAD_GATEWAY, CODE_UPSTREAM_ERROR, MESSAGE_UPSTREAM_TEMPORARILY_UNAVAILABLE);
            default -> new Decision(HTTP_BAD_GATEWAY, CODE_UPSTREAM_ERROR, MESSAGE_UPSTREAM_REQUEST_FAILED);
        };
    }

    private static Decision mapOpenAiGatewayStyle(int upstreamStatusCode) {
        return switch (upstreamStatusCode) {
            case 401 -> new Decision(HTTP_BAD_GATEWAY, CODE_UPSTREAM_ERROR, MESSAGE_UPSTREAM_AUTH_FAILED);
            case 403 -> new Decision(HTTP_BAD_GATEWAY, CODE_UPSTREAM_ERROR, MESSAGE_UPSTREAM_ACCESS_FORBIDDEN);
            case 429 -> new Decision(HTTP_TOO_MANY_REQUESTS, CODE_RATE_LIMIT_ERROR, MESSAGE_UPSTREAM_RATE_LIMIT);
            case 529 -> new Decision(HTTP_SERVICE_UNAVAILABLE, CODE_UPSTREAM_ERROR, MESSAGE_UPSTREAM_OVERLOADED);
            case 500, 502, 503, 504 ->
                    new Decision(HTTP_BAD_GATEWAY, CODE_UPSTREAM_ERROR, MESSAGE_UPSTREAM_TEMPORARILY_UNAVAILABLE);
            default -> new Decision(HTTP_BAD_GATEWAY, CODE_UPSTREAM_ERROR, MESSAGE_UPSTREAM_REQUEST_FAILED);
        };
    }

    private static int nonZeroStatus(int upstreamStatusCode) {
        return upstreamStatusCode > 0 ? upstreamStatusCode : HTTP_BAD_GATEWAY;
    }

    public record Decision(int status, String code, String message) {
    }
}
