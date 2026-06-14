package com.landgate.trigger.gateway.oauth;

import com.landgate.infrastructure.config.OAuthProperties;
import com.landgate.types.gateway.GatewayHttpHeaderPolicy;

import java.net.http.HttpRequest;

/**
 * OAuth authorization-flow HTTP facts and request-builder helpers.
 *
 * <p>This profile is intentionally separate from gateway upstream auth
 * profiles. It owns OAuth token/device-code endpoint transport details only and
 * must not build gateway upstream requests or translate model protocols.</p>
 */
final class OAuthHttpProfile {

    static final String TOKEN_EXCHANGE_FORMAT_JSON = "json";

    private OAuthHttpProfile() {
    }

    static boolean usesJsonTokenExchange(OAuthProperties.ProviderConfig provider) {
        return provider != null
                && TOKEN_EXCHANGE_FORMAT_JSON.equalsIgnoreCase(provider.getTokenExchangeFormat());
    }

    static String tokenExchangeContentType(OAuthProperties.ProviderConfig provider) {
        return usesJsonTokenExchange(provider)
                ? GatewayHttpHeaderPolicy.MEDIA_TYPE_JSON
                : GatewayHttpHeaderPolicy.MEDIA_TYPE_FORM_URLENCODED;
    }

    static HttpRequest.Builder applyContentType(HttpRequest.Builder builder, String contentType) {
        return builder.header(GatewayHttpHeaderPolicy.HEADER_CONTENT_TYPE, contentType);
    }

    static HttpRequest.Builder applyJsonContentType(HttpRequest.Builder builder) {
        return applyContentType(builder, GatewayHttpHeaderPolicy.MEDIA_TYPE_JSON);
    }

    static void applyProviderUserAgent(HttpRequest.Builder builder,
                                       OAuthProperties.ProviderConfig provider) {
        if (provider != null && provider.getUserAgent() != null && !provider.getUserAgent().isEmpty()) {
            builder.header(GatewayHttpHeaderPolicy.HEADER_USER_AGENT, provider.getUserAgent());
        }
    }
}
