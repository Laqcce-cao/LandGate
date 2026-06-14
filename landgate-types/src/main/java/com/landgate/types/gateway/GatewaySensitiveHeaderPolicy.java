package com.landgate.types.gateway;

import java.util.Set;

/**
 * Stable inbound credential/sensitive header facts.
 *
 * <p>This type owns names of client-supplied headers that must not be forwarded
 * by default. It must not authenticate requests, build upstream credentials, or
 * decide profile-specific allowlists.</p>
 */
public final class GatewaySensitiveHeaderPolicy {

    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_COOKIE = "Cookie";
    public static final String HEADER_PROXY_AUTHORIZATION = "Proxy-Authorization";
    public static final String HEADER_X_API_KEY = "x-api-key";
    public static final String HEADER_X_GOOG_API_KEY = "x-goog-api-key";

    public static final Set<String> SENSITIVE_REQUEST_HEADERS = Set.of(
            headerKey(HEADER_AUTHORIZATION),
            headerKey(HEADER_COOKIE),
            headerKey(HEADER_PROXY_AUTHORIZATION),
            headerKey(HEADER_X_API_KEY),
            headerKey(HEADER_X_GOOG_API_KEY));

    private GatewaySensitiveHeaderPolicy() {
    }

    public static String headerKey(String name) {
        return GatewayHeaderPolicy.headerKey(name);
    }
}
