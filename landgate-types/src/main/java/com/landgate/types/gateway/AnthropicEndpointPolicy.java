package com.landgate.types.gateway;

/**
 * Stable Anthropic endpoint URL/path facts.
 *
 * <p>This type owns only base URLs and path constants. It must not build auth
 * headers, choose accounts, translate protocols, normalize bodies, parse
 * responses, or perform billing.</p>
 */
public final class AnthropicEndpointPolicy {

    public static final String API_BASE_URL = "https://api.anthropic.com";
    public static final String MESSAGES_PATH = "/v1/messages";

    private AnthropicEndpointPolicy() {
    }
}
