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
            OpenAiApiProfile.headerKey(OpenAiApiProfile.HEADER_ACCEPT),
            OpenAiApiProfile.headerKey(OpenAiApiProfile.HEADER_ACCEPT_LANGUAGE),
            OpenAiApiProfile.headerKey(OpenAiApiProfile.HEADER_CONTENT_TYPE),
            OpenAiApiProfile.headerKey(OpenAiCodexProfile.HEADER_CONVERSATION_ID),
            OpenAiApiProfile.headerKey(OpenAiCodexProfile.HEADER_OPENAI_BETA),
            OpenAiApiProfile.headerKey(OpenAiCodexProfile.HEADER_ORIGINATOR),
            OpenAiApiProfile.headerKey(OpenAiCodexProfile.HEADER_SESSION_ID),
            OpenAiApiProfile.headerKey(OpenAiApiProfile.HEADER_USER_AGENT),
            OpenAiApiProfile.headerKey(OpenAiCodexProfile.HEADER_X_CODEX_TURN_STATE),
            OpenAiApiProfile.headerKey(OpenAiCodexProfile.HEADER_X_CODEX_TURN_METADATA));

    public static final Set<String> API_KEY_RESPONSES_ALLOWED_HEADERS = Set.of(
            OpenAiApiProfile.headerKey(OpenAiApiProfile.HEADER_ACCEPT_LANGUAGE),
            OpenAiApiProfile.headerKey(OpenAiApiProfile.HEADER_USER_AGENT));

    public static final Set<String> RAW_CHAT_ALLOWED_HEADERS = Set.of(
            OpenAiApiProfile.headerKey(OpenAiApiProfile.HEADER_ACCEPT_LANGUAGE),
            OpenAiApiProfile.headerKey(OpenAiApiProfile.HEADER_USER_AGENT));

    private OpenAiHeaderPolicy() {
    }
}
