package com.landgate.types.gateway;

import java.util.concurrent.TimeUnit;

/**
 * Stable storage/key policy for OpenAI Responses compatibility sessions.
 *
 * <p>This type owns cache names, TTL, and key derivation only. It must not read
 * or write Redis, mutate request bodies, select accounts, perform auth, or
 * translate protocols.</p>
 */
public final class OpenAiCompatSessionPolicy {

    public static final long TTL_VALUE = 30;
    public static final TimeUnit TTL_UNIT = TimeUnit.MINUTES;

    public static final String RESPONSE_ID_CACHE = "openai:compat:response-id";
    public static final String TURN_STATE_CACHE = "openai:compat:turn-state";
    public static final String CONTINUATION_DISABLED_CACHE = "openai:compat:continuation-disabled";
    public static final String DIGEST_CACHE = "openai:compat:anthropic-digest";

    public static final String SESSION_KEY_SEPARATOR = "\u0000";
    public static final String DIGEST_NAMESPACE_SEPARATOR = "|";
    public static final int ANTHROPIC_REPLAY_MAX_TAIL_MESSAGES = 12;

    private OpenAiCompatSessionPolicy() {
    }

    public static String sessionKey(Long accountId, Long apiKeyId, String promptCacheKey) {
        if (accountId == null || trim(promptCacheKey).isBlank()) return "";
        return accountId
                + SESSION_KEY_SEPARATOR
                + (apiKeyId == null ? 0L : apiKeyId)
                + SESSION_KEY_SEPARATOR
                + trim(promptCacheKey);
    }

    public static String digestNamespace(Long accountId, Long apiKeyId) {
        if (accountId == null || accountId <= 0) return "";
        return accountId
                + DIGEST_NAMESPACE_SEPARATOR
                + (apiKeyId == null ? 0L : apiKeyId)
                + DIGEST_NAMESPACE_SEPARATOR;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
