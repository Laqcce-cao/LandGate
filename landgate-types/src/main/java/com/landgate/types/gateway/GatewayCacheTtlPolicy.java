package com.landgate.types.gateway;

/**
 * Shared cache-control TTL policy facts used by Anthropic-compatible request
 * and usage accounting paths.
 */
public final class GatewayCacheTtlPolicy {

    public static final String TARGET_5M = "5m";
    public static final String TARGET_1H = "1h";

    public static final String EXTRA_CACHE_TTL_OVERRIDE_ENABLED = "cache_ttl_override_enabled";
    public static final String EXTRA_CACHE_TTL_OVERRIDE_TARGET = "cache_ttl_override_target";

    private GatewayCacheTtlPolicy() {
    }

    public static boolean isSupportedTarget(String target) {
        return TARGET_5M.equals(target) || TARGET_1H.equals(target);
    }

    public static String normalizeTarget(String target) {
        String normalized = target == null ? "" : target.trim();
        return isSupportedTarget(normalized) ? normalized : TARGET_5M;
    }
}
