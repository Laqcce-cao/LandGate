package com.landgate.types.gateway;

import com.landgate.types.enums.Platform;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Client-facing gateway route facts.
 * <p>
 * This type is intentionally limited to path and client protocol metadata. It
 * must not contain upstream URL construction, auth, normalization, conversion,
 * streaming, usage, or billing behavior.
 */
public enum GatewayClientRoute {

    MESSAGES("/v1/messages", Platform.ANTHROPIC, GatewayProtocolFormat.MESSAGES),
    CHAT_COMPLETIONS("/v1/chat/completions", Platform.OPENAI, GatewayProtocolFormat.CHAT_COMPLETIONS),
    CHAT_COMPLETIONS_ALIAS("/chat/completions", Platform.OPENAI, GatewayProtocolFormat.CHAT_COMPLETIONS),
    CODEX_RESPONSES(GatewayResponsesRoutePolicy.CODEX_RESPONSES_PATH, Platform.OPENAI, GatewayProtocolFormat.RESPONSES),
    V1_RESPONSES(GatewayResponsesRoutePolicy.V1_RESPONSES_PATH, Platform.OPENAI, GatewayProtocolFormat.RESPONSES),
    RESPONSES_ALIAS(GatewayResponsesRoutePolicy.RESPONSES_ALIAS_PATH, Platform.OPENAI, GatewayProtocolFormat.RESPONSES);

    private static final List<GatewayClientRoute> PREFIX_MATCH_ORDER = Arrays.stream(values())
            .sorted(Comparator.comparingInt((GatewayClientRoute route) -> route.pathPrefix.length()).reversed())
            .toList();

    private final String pathPrefix;
    private final Platform platform;
    private final GatewayProtocolFormat format;

    GatewayClientRoute(String pathPrefix, Platform platform, GatewayProtocolFormat format) {
        this.pathPrefix = pathPrefix;
        this.platform = platform;
        this.format = format;
    }

    public String pathPrefix() {
        return pathPrefix;
    }

    public Platform platform() {
        return platform;
    }

    public String format() {
        return format.id();
    }

    public GatewayProtocolFormat formatType() {
        return format;
    }

    public boolean matches(String path) {
        return path != null && path.startsWith(pathPrefix);
    }

    public static Optional<GatewayClientRoute> resolve(String path) {
        return PREFIX_MATCH_ORDER.stream()
                .filter(route -> route.matches(path))
                .findFirst();
    }
}
