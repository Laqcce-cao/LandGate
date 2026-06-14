package com.landgate.types.gateway;

import java.util.Set;

/**
 * Stable OpenAI upstream header allowlists split by upstream trust domain.
 *
 * <p>This type owns header allowlist facts only. It must not build auth
 * headers, choose routes, normalize bodies, or copy client headers.</p>
 */
public final class OpenAiHeaderPolicy {

    public static final Set<String> CODEX_OAUTH_ALLOWED_HEADERS = Set.of(
            OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_ACCEPT),
            OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_ACCEPT_LANGUAGE),
            OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_CONTENT_TYPE),
            OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_CONVERSATION_ID),
            OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_OPENAI_BETA),
            OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_ORIGINATOR),
            OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_SESSION_ID),
            OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_USER_AGENT),
            OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_X_CODEX_TURN_STATE),
            OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_X_CODEX_TURN_METADATA));

    public static final Set<String> API_KEY_RESPONSES_ALLOWED_HEADERS = Set.of(
            OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_ACCEPT_LANGUAGE),
            OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_USER_AGENT));

    public static final Set<String> RAW_CHAT_ALLOWED_HEADERS = Set.of(
            OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_ACCEPT_LANGUAGE),
            OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_USER_AGENT));

    private OpenAiHeaderPolicy() {
    }
}
