package com.landgate.types.gateway;

import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;

/**
 * Stable route applicability rules for Sub2API-compatible gateway behavior.
 *
 * <p>This policy owns only account-platform/account-type and protocol-route
 * predicates. It must not mutate request bodies, build auth headers, update
 * sessions, write responses, select accounts, or execute retries.</p>
 */
public final class GatewayRouteCompatibilityPolicy {

    private GatewayRouteCompatibilityPolicy() {
    }

    public static boolean isOpenAiResponsesRetryEligible(Platform accountPlatform,
                                                         Platform upstreamPlatform,
                                                         String upstreamFormat) {
        return accountPlatform == Platform.OPENAI
                && upstreamPlatform == Platform.OPENAI
                && GatewayProtocolFormat.RESPONSES.is(upstreamFormat);
    }

    public static boolean isAnthropicMessagesRetryEligible(Platform accountPlatform,
                                                           Platform upstreamPlatform,
                                                           String upstreamFormat) {
        return accountPlatform == Platform.ANTHROPIC
                && upstreamPlatform == Platform.ANTHROPIC
                && GatewayProtocolFormat.MESSAGES.is(upstreamFormat);
    }

    public static boolean isOpenAiAnthropicMessagesCompat(Platform upstreamPlatform,
                                                          String clientFormat,
                                                          String upstreamFormat) {
        return upstreamPlatform == Platform.OPENAI
                && isAnthropicMessagesClientFormat(clientFormat)
                && GatewayProtocolFormat.RESPONSES.is(upstreamFormat);
    }

    public static boolean isAnthropicMessagesClientFormat(String clientFormat) {
        return GatewayProtocolFormat.MESSAGES.is(clientFormat);
    }

    public static boolean isAnthropicClientPlatform(Platform requestPlatform) {
        return requestPlatform == Platform.ANTHROPIC;
    }

    public static boolean isOpenAiOAuthChatCompletionsToCodexResponsesCompat(Platform accountPlatform,
                                                                             AccountType accountType,
                                                                             Platform upstreamPlatform,
                                                                             boolean normalizeCodexOAuthBody,
                                                                             String clientFormat,
                                                                             String upstreamFormat) {
        return accountPlatform == Platform.OPENAI
                && accountType == AccountType.OAUTH
                && upstreamPlatform == Platform.OPENAI
                && normalizeCodexOAuthBody
                && GatewayProtocolFormat.CHAT_COMPLETIONS.is(clientFormat)
                && GatewayProtocolFormat.RESPONSES.is(upstreamFormat);
    }
}
