package com.landgate.types.gateway;

import java.util.Locale;

/**
 * Stable generic OpenAI API header/media/auth facts.
 *
 * <p>This type owns only protocol-neutral OpenAI API facts. Codex-specific
 * headers, client markers, model aliases, and quota headers belong in
 * {@link OpenAiCodexProfile}.</p>
 */
public final class OpenAiApiProfile {

    public static final String HEADER_ACCEPT = "Accept";
    public static final String HEADER_ACCEPT_LANGUAGE = "Accept-Language";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_USER_AGENT = "User-Agent";

    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String ACCEPT_EVENT_STREAM = "text/event-stream";
    public static final String ACCEPT_JSON = "application/json";
    public static final String AUTH_BEARER_PREFIX = "Bearer ";

    private OpenAiApiProfile() {
    }

    public static String bearerToken(String accessToken) {
        return AUTH_BEARER_PREFIX + accessToken;
    }

    public static String headerKey(String headerName) {
        return headerName == null ? "" : headerName.trim().toLowerCase(Locale.ROOT);
    }
}
