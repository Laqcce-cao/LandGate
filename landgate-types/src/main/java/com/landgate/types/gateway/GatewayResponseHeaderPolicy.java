package com.landgate.types.gateway;

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
    public static final String CODEX_RESPONSE_HEADER_PREFIX = "x-codex-";

    private static final Set<String> DEFAULT_ALLOWED_HEADERS = Set.of(
            HEADER_CONTENT_TYPE,
            "content-encoding",
            "content-language",
            "cache-control",
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
}
