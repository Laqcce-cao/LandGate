package com.landgate.types.gateway;

import java.util.Map;
import java.util.Set;

/**
 * Stable upstream response header filtering policy for gateway routes.
 *
 * <p>This mirrors Sub2API's default response header filter: pass a small safe
 * allowlist, skip hop-by-hop headers, and allow Codex internal headers only on
 * passthrough routes. The policy only decides names; response services still
 * own actual servlet writes.</p>
 */
public final class GatewayResponseHeaderPolicy {

    public static final String HEADER_CONTENT_TYPE = "content-type";
    public static final String HEADER_CONTENT_LENGTH = "content-length";
    public static final String HEADER_TRANSFER_ENCODING = "transfer-encoding";
    public static final String HEADER_CONNECTION = "connection";
    public static final String HEADER_CACHE_CONTROL = "cache-control";
    public static final String HEADER_X_ACCEL_BUFFERING = "x-accel-buffering";
    public static final String VALUE_NO_CACHE = "no-cache";
    public static final String VALUE_KEEP_ALIVE = "keep-alive";
    public static final String VALUE_NO = "no";
    public static final String CODEX_RESPONSE_HEADER_PREFIX = "x-codex-";

    private static final Set<String> DEFAULT_ALLOWED_HEADERS = Set.of(
            HEADER_CONTENT_TYPE,
            "content-encoding",
            "content-language",
            HEADER_CACHE_CONTROL,
            "etag",
            "last-modified",
            "expires",
            "vary",
            "date",
            "x-request-id",
            "x-ratelimit-limit-requests",
            "x-ratelimit-limit-tokens",
            "x-ratelimit-remaining-requests",
            "x-ratelimit-remaining-tokens",
            "x-ratelimit-reset-requests",
            "x-ratelimit-reset-tokens",
            "retry-after",
            "location",
            "www-authenticate");

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            HEADER_CONTENT_LENGTH,
            HEADER_TRANSFER_ENCODING,
            HEADER_CONNECTION);

    private GatewayResponseHeaderPolicy() {
    }

    public static Map<String, String> streamingResponseHeaders() {
        return Map.of(
                canonicalHeaderName(HEADER_CACHE_CONTROL), VALUE_NO_CACHE,
                canonicalHeaderName(HEADER_CONNECTION), VALUE_KEEP_ALIVE,
                canonicalHeaderName(HEADER_X_ACCEL_BUFFERING), VALUE_NO);
    }

    public static Set<String> defaultAllowedHeaders() {
        return DEFAULT_ALLOWED_HEADERS;
    }

    public static Set<String> hopByHopHeaders() {
        return HOP_BY_HOP_HEADERS;
    }

    public static boolean shouldCopy(String headerName, boolean passthrough) {
        String normalized = normalize(headerName);
        if (normalized.isBlank()) {
            return false;
        }
        if (HOP_BY_HOP_HEADERS.contains(normalized)) {
            return false;
        }
        if (HEADER_CONTENT_TYPE.equals(normalized)) {
            return false;
        }
        return DEFAULT_ALLOWED_HEADERS.contains(normalized)
                || (passthrough && normalized.startsWith(CODEX_RESPONSE_HEADER_PREFIX));
    }

    private static String normalize(String headerName) {
        return headerName == null ? "" : headerName.trim().toLowerCase();
    }

    private static String canonicalHeaderName(String headerName) {
        return switch (normalize(headerName)) {
            case HEADER_CACHE_CONTROL -> "Cache-Control";
            case HEADER_CONNECTION -> "Connection";
            case HEADER_X_ACCEL_BUFFERING -> "X-Accel-Buffering";
            default -> headerName;
        };
    }
}
