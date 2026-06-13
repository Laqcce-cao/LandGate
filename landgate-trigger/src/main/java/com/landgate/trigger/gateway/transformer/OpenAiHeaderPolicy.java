package com.landgate.trigger.gateway.transformer;

import java.util.Set;

/**
 * Header allowlists are split by upstream trust domain, matching sub2api.
 */
final class OpenAiHeaderPolicy {

    static final Set<String> CODEX_OAUTH_ALLOWED_HEADERS = Set.of(
            "accept",
            "accept-language",
            "content-type",
            "conversation_id",
            "openai-beta",
            "originator",
            "session_id",
            "user-agent",
            "x-codex-turn-state",
            "x-codex-turn-metadata");

    static final Set<String> API_KEY_RESPONSES_ALLOWED_HEADERS = Set.of(
            "accept",
            "accept-language",
            "content-type",
            "conversation_id",
            "openai-beta",
            "originator",
            "session_id",
            "user-agent",
            "x-codex-turn-state",
            "x-codex-turn-metadata");

    static final Set<String> RAW_CHAT_ALLOWED_HEADERS = Set.of(
            "accept-language",
            "user-agent");

    private OpenAiHeaderPolicy() {
    }
}
