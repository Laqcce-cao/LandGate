package com.landgate.types.gateway;

/**
 * Common HTTP header/media-type facts shared by gateway modules.
 *
 * <p>This type owns transport-level names only. Protocol-specific headers stay
 * in Anthropic/OpenAI profiles, and this class must not build requests, perform
 * auth, normalize bodies, or select routes.</p>
 */
public final class GatewayHttpHeaderPolicy {

    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_USER_AGENT = "User-Agent";
    public static final String MEDIA_TYPE_FORM_URLENCODED = "application/x-www-form-urlencoded";
    public static final String MEDIA_TYPE_JSON = "application/json";
    public static final String MEDIA_TYPE_JSON_UTF8 = "application/json;charset=UTF-8";

    private GatewayHttpHeaderPolicy() {
    }
}
