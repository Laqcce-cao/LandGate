package com.landgate.trigger.gateway.route;

import com.landgate.domain.account.model.entity.AccountEntity;

/**
 * Public read-only facade for upstream endpoint defaults.
 *
 * <p>Endpoint URL construction remains owned by {@link UpstreamEndpointProfile};
 * this facade exists for legacy compatibility paths that do not have a resolved
 * {@link UpstreamRoute} yet.</p>
 */
public final class UpstreamEndpointDefaults {

    private UpstreamEndpointDefaults() {
    }

    public static String openAiChatCompletionsUrl() {
        return UpstreamEndpointProfile.OPENAI_CHAT_COMPLETIONS.defaultUrl();
    }

    public static String openAiCodexResponsesUrl() {
        return UpstreamEndpointProfile.OPENAI_CODEX_RESPONSES.defaultUrl();
    }

    public static String anthropicMessagesUrl() {
        return UpstreamEndpointProfile.ANTHROPIC_MESSAGES.defaultUrl();
    }

    public static String anthropicMessagesUrl(AccountEntity account) {
        return UpstreamEndpointProfile.ANTHROPIC_MESSAGES.targetUrl(
                UpstreamRouteRequest.builder().account(account).build());
    }

    public static String anthropicMessagesCountTokensUrl(AccountEntity account) {
        return UpstreamEndpointProfile.ANTHROPIC_MESSAGES_COUNT_TOKENS.targetUrl(
                UpstreamRouteRequest.builder().account(account).build());
    }
}
