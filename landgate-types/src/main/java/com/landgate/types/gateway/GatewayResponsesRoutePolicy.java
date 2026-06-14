package com.landgate.types.gateway;

/**
 * Shared path policy for OpenAI Responses-compatible client routes.
 *
 * <p>This type owns path canonicalization only. It must not build full upstream
 * URLs, choose accounts, perform auth, normalize request bodies, or translate
 * protocols.</p>
 */
public final class GatewayResponsesRoutePolicy {

    public static final String V1_RESPONSES_PATH = "/v1/responses";
    public static final String RESPONSES_ALIAS_PATH = "/responses";
    public static final String CODEX_RESPONSES_PATH = "/backend-api/codex/responses";
    public static final String COMPACT_SUBPATH = "/compact";

    private GatewayResponsesRoutePolicy() {
    }

    public static String canonicalClientUpstreamPath(String servletPath) {
        if (servletPath == null || servletPath.isBlank()) {
            return V1_RESPONSES_PATH;
        }
        if (servletPath.startsWith(V1_RESPONSES_PATH)) {
            return servletPath;
        }
        if (servletPath.startsWith(RESPONSES_ALIAS_PATH)) {
            return "/v1" + servletPath;
        }
        if (servletPath.startsWith(CODEX_RESPONSES_PATH)) {
            return servletPath;
        }
        return V1_RESPONSES_PATH;
    }

    public static String openAiPublicResponsesPath(String upstreamPath) {
        if (upstreamPath == null || upstreamPath.isBlank()) {
            return V1_RESPONSES_PATH;
        }
        if (upstreamPath.startsWith(V1_RESPONSES_PATH)) {
            return upstreamPath;
        }
        if (upstreamPath.startsWith(RESPONSES_ALIAS_PATH)) {
            return "/v1" + upstreamPath;
        }
        if (upstreamPath.startsWith(CODEX_RESPONSES_PATH)) {
            return V1_RESPONSES_PATH + upstreamPath.substring(CODEX_RESPONSES_PATH.length());
        }
        return V1_RESPONSES_PATH;
    }

    public static String codexResponsesPath(String upstreamPath) {
        if (upstreamPath == null || upstreamPath.isBlank()) {
            return CODEX_RESPONSES_PATH;
        }
        if (upstreamPath.startsWith(CODEX_RESPONSES_PATH)) {
            return upstreamPath;
        }
        if (upstreamPath.startsWith(V1_RESPONSES_PATH)) {
            return CODEX_RESPONSES_PATH + upstreamPath.substring(V1_RESPONSES_PATH.length());
        }
        if (upstreamPath.startsWith(RESPONSES_ALIAS_PATH)) {
            return CODEX_RESPONSES_PATH + upstreamPath.substring(RESPONSES_ALIAS_PATH.length());
        }
        return CODEX_RESPONSES_PATH;
    }

    public static boolean isCompactPath(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            return false;
        }
        String value = pathOrUrl.trim();
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        return value.endsWith(COMPACT_SUBPATH) || value.contains(COMPACT_SUBPATH + "/");
    }
}
