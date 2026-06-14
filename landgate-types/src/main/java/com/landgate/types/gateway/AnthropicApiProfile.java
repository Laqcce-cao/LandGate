package com.landgate.types.gateway;

/**
 * Stable Anthropic API header/profile facts.
 *
 * <p>This type owns header names, media types, stable version values, and small
 * auth string helpers only. It must not build HTTP requests, select accounts,
 * translate protocols, normalize bodies, parse usage, or perform billing.</p>
 */
public final class AnthropicApiProfile {

    public static final String ANTHROPIC_VERSION = "2023-06-01";
    public static final String BETA_OAUTH = "oauth-2025-04-20";

    public static final String HEADER_ACCEPT = "Accept";
    public static final String HEADER_ACCEPT_ENCODING = "Accept-Encoding";
    public static final String HEADER_ACCEPT_LANGUAGE = "Accept-Language";
    public static final String HEADER_ANTHROPIC_BETA = "anthropic-beta";
    public static final String HEADER_ANTHROPIC_VERSION = "anthropic-version";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_SEC_FETCH_MODE = "Sec-Fetch-Mode";
    public static final String HEADER_USER_AGENT = "User-Agent";
    public static final String HEADER_X_API_KEY = "x-api-key";
    public static final String HEADER_X_APP = "X-App";
    public static final String HEADER_X_CLAUDE_CODE_SESSION_ID = "X-Claude-Code-Session-Id";
    public static final String HEADER_STAINLESS_HELPER_METHOD = "x-stainless-helper-method";
    public static final String HEADER_X_CLIENT_REQUEST_ID = "x-client-request-id";

    public static final String AUTH_BEARER_PREFIX = "Bearer ";
    public static final String MEDIA_TYPE_JSON = "application/json";
    public static final String STAINLESS_HELPER_METHOD_STREAM = "stream";
    public static final String X_APP_CLI = "cli";

    private AnthropicApiProfile() {
    }

    public static String bearerToken(String accessToken) {
        return AUTH_BEARER_PREFIX + accessToken;
    }
}
