package com.landgate.types.gateway;

import java.util.Set;

/**
 * Stable web-search tool aliases shared by protocol converters.
 *
 * <p>This type owns tool names/type aliases and small membership checks only.
 * It must not parse or mutate request bodies, select routes, perform auth, or
 * build upstream requests.</p>
 */
public final class GatewayWebSearchToolPolicy {

    public static final String TOOL_NAME_WEB_SEARCH = "web_search";
    public static final String TYPE_GOOGLE_SEARCH = "google_search";
    public static final String TYPE_WEB_SEARCH = "web_search";
    public static final String TYPE_WEB_SEARCH_PREVIEW = "web_search_preview";
    public static final String TYPE_WEB_SEARCH_20250305 = "web_search_20250305";

    private static final Set<String> WEB_SEARCH_TOOL_TYPES = Set.of(
            TYPE_GOOGLE_SEARCH,
            TYPE_WEB_SEARCH,
            TYPE_WEB_SEARCH_PREVIEW,
            TYPE_WEB_SEARCH_20250305);

    private GatewayWebSearchToolPolicy() {
    }

    public static boolean isWebSearchToolType(String type) {
        return type != null && WEB_SEARCH_TOOL_TYPES.contains(type.trim());
    }

    public static boolean isAnthropicServerWebSearchToolType(String type) {
        return type != null && type.trim().startsWith(TYPE_WEB_SEARCH);
    }
}
