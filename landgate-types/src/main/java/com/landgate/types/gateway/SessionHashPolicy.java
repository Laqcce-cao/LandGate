package com.landgate.types.gateway;

import java.util.concurrent.TimeUnit;

/**
 * Storage/key policy for gateway sticky sessions.
 *
 * <p>This type owns sticky-session cache names, TTL, namespace prefixes, and
 * pure key material helpers only. It must not read requests, use Redis, choose
 * accounts, perform auth, or translate protocols.</p>
 */
public final class SessionHashPolicy {

    public static final long TTL_VALUE = 30;
    public static final TimeUnit TTL_UNIT = TimeUnit.MINUTES;
    public static final String CACHE_KEY = "session:sticky";

    public static final String KEY_SEPARATOR = "|";
    public static final String PROMPT_CACHE_KEY_HASH_NAMESPACE =
            OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_KEY + KEY_SEPARATOR;
    public static final String ANTHROPIC_CACHE_HASH_NAMESPACE = "anthropic_cache" + KEY_SEPARATOR;
    public static final String OPENAI_CONTENT_SEED_HASH_NAMESPACE = "openai_content_seed" + KEY_SEPARATOR;
    public static final String SEMVER_PATTERN = "\\d+\\.\\d+\\.\\d+";
    public static final String SEMVER_PLACEHOLDER = "X.Y.Z";

    private SessionHashPolicy() {
    }

    public static String promptCacheKeyMaterial(Long apiKeyId, String promptCacheKey) {
        return PROMPT_CACHE_KEY_HASH_NAMESPACE + apiKeyId + KEY_SEPARATOR + trim(promptCacheKey);
    }

    public static String anthropicCacheAnchorMaterial(Long apiKeyId, String anchor) {
        return ANTHROPIC_CACHE_HASH_NAMESPACE + apiKeyId + KEY_SEPARATOR + trim(anchor);
    }

    public static String openAiContentSeedMaterial(Long apiKeyId, String seed) {
        return OPENAI_CONTENT_SEED_HASH_NAMESPACE + apiKeyId + KEY_SEPARATOR + trim(seed);
    }

    public static String requestContextMaterial(String clientIp, String userAgent, Long apiKeyId) {
        return trim(clientIp)
                + KEY_SEPARATOR
                + normalizeUserAgent(userAgent)
                + KEY_SEPARATOR
                + apiKeyId;
    }

    public static boolean usesOpenAiContentSeedFallback(String requestFormat) {
        return GatewayProtocolFormat.RESPONSES.is(requestFormat)
                || GatewayProtocolFormat.CHAT_COMPLETIONS.is(requestFormat);
    }

    public static String normalizeUserAgent(String userAgent) {
        if (userAgent == null) return "";
        return userAgent.replaceAll(SEMVER_PATTERN, SEMVER_PLACEHOLDER);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
