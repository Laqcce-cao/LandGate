package com.landgate.trigger.gateway.route;

import com.landgate.types.gateway.GatewayResponsesRoutePolicy;
import com.landgate.types.gateway.AnthropicCountTokensPolicy;

/**
 * Centralized endpoint URL and path policy for upstream protocol routes.
 *
 * <p>This profile deliberately does not own auth, protocol conversion, request
 * normalization, streaming translation, billing, or usage parsing.</p>
 */
enum UpstreamEndpointProfile {

    OPENAI_RESPONSES(EndpointKind.OPENAI_RESPONSES, "https://api.openai.com", "/v1/responses") {
        @Override
        String routePath(UpstreamRouteRequest request) {
            return GatewayResponsesRoutePolicy.openAiPublicResponsesPath(
                    request == null ? null : request.upstreamPath());
        }

        @Override
        String targetUrl(UpstreamRouteRequest request) {
            return openAiPublicUrl(request, routePath(request));
        }
    },

    OPENAI_CHAT_COMPLETIONS(EndpointKind.OPENAI_CHAT_COMPLETIONS, "https://api.openai.com", "/v1/chat/completions") {
        @Override
        String targetUrl(UpstreamRouteRequest request) {
            return openAiPublicUrl(request, routePath(request));
        }
    },

    OPENAI_CODEX_RESPONSES(EndpointKind.OPENAI_CODEX_RESPONSES, "https://chatgpt.com", "/backend-api/codex/responses") {
        @Override
        String routePath(UpstreamRouteRequest request) {
            return GatewayResponsesRoutePolicy.codexResponsesPath(
                    request == null ? null : request.upstreamPath());
        }

        @Override
        String targetUrl(UpstreamRouteRequest request) {
            return defaultBaseUrl() + routePath(request);
        }
    },

    ANTHROPIC_MESSAGES(EndpointKind.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "/v1/messages") {
        @Override
        String targetUrl(UpstreamRouteRequest request) {
            return AccountRouteOptions.baseUrl(request == null ? null : request.account())
                    .map(baseUrl -> trimTrailingSlashes(baseUrl) + routePath(request))
                    .orElseGet(this::defaultUrl);
        }
    },

    ANTHROPIC_MESSAGES_COUNT_TOKENS(
            EndpointKind.ANTHROPIC_MESSAGES_COUNT_TOKENS,
            "https://api.anthropic.com",
            AnthropicCountTokensPolicy.UPSTREAM_PATH_WITH_QUERY) {
        @Override
        String targetUrl(UpstreamRouteRequest request) {
            return AccountRouteOptions.baseUrl(request == null ? null : request.account())
                    .map(baseUrl -> trimTrailingSlashes(baseUrl) + routePath(request))
                    .orElseGet(this::defaultUrl);
        }
    };

    private final EndpointKind endpointKind;
    private final String defaultBaseUrl;
    private final String defaultPath;

    UpstreamEndpointProfile(EndpointKind endpointKind, String defaultBaseUrl, String defaultPath) {
        this.endpointKind = endpointKind;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultPath = defaultPath;
    }

    EndpointKind endpointKind() {
        return endpointKind;
    }

    String routePath(UpstreamRouteRequest request) {
        return defaultPath;
    }

    String defaultUrl() {
        return defaultBaseUrl + defaultPath;
    }

    String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    String targetUrl(UpstreamRouteRequest request) {
        return defaultUrl();
    }

    private static String openAiPublicUrl(UpstreamRouteRequest request, String pathSuffix) {
        return AccountRouteOptions.baseUrl(request == null ? null : request.account())
                .map(baseUrl -> buildOpenAiEndpointUrl(baseUrl, pathSuffix))
                .orElse("https://api.openai.com" + pathSuffix);
    }

    static String buildOpenAiEndpointUrl(String baseUrl, String pathSuffix) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.openai.com" + pathSuffix;
        }
        String base = trimTrailingSlashes(baseUrl);
        String suffix = pathSuffix == null || pathSuffix.isBlank()
                ? GatewayResponsesRoutePolicy.V1_RESPONSES_PATH
                : pathSuffix;
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }

        if (suffix.startsWith("/v1/chat/completions")) {
            if (base.endsWith("/v1/chat/completions") || base.endsWith("/chat/completions")) {
                return base + suffix.substring("/v1/chat/completions".length());
            }
            if (base.endsWith("/v1")) {
                return base + suffix.substring("/v1".length());
            }
            return base + suffix;
        }

        if (suffix.startsWith(GatewayResponsesRoutePolicy.V1_RESPONSES_PATH)) {
            if (base.endsWith(GatewayResponsesRoutePolicy.V1_RESPONSES_PATH)
                    || base.endsWith(GatewayResponsesRoutePolicy.RESPONSES_ALIAS_PATH)) {
                return base + suffix.substring(GatewayResponsesRoutePolicy.V1_RESPONSES_PATH.length());
            }
            if (base.endsWith("/v1")) {
                return base + suffix.substring("/v1".length());
            }
            return base + suffix;
        }

        return base + suffix;
    }

    private static String trimTrailingSlashes(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
