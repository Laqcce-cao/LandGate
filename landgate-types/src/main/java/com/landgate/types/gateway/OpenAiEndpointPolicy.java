package com.landgate.types.gateway;

/**
 * Stable OpenAI endpoint URL/path facts.
 *
 * <p>This type owns only base URLs, path constants, and small path predicates.
 * It must not build auth headers, choose accounts, translate protocols,
 * normalize bodies, parse responses, or perform billing.</p>
 */
public final class OpenAiEndpointPolicy {

    public static final String PUBLIC_API_BASE_URL = "https://api.openai.com";
    public static final String CODEX_BASE_URL = "https://chatgpt.com";

    public static final String V1_PREFIX = "/v1";
    public static final String V1_CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    public static final String CHAT_COMPLETIONS_ALIAS_PATH = "/chat/completions";

    private OpenAiEndpointPolicy() {
    }

    public static boolean isChatCompletionsPath(String path) {
        return path != null
                && (path.startsWith(V1_CHAT_COMPLETIONS_PATH)
                || path.startsWith(CHAT_COMPLETIONS_ALIAS_PATH));
    }
}
