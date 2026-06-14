package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.gateway.GatewayHeaderPolicy;
import com.landgate.types.gateway.GatewayProtocolFormat;
import com.landgate.types.gateway.OpenAiCodexProfile;
import com.landgate.types.gateway.OpenAiHeaderPolicy;
import com.landgate.types.gateway.OpenAiSessionIdPolicy;

import java.util.Map;

/**
 * OpenAI upstream auth/header profile.
 *
 * <p>The profile owns only credential/header mapping. Endpoint selection and
 * request body normalization stay in route strategy and normalizer code.</p>
 */
final class OpenAiAuthProfile {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiAuthProfile() {
    }

    static UpstreamHeaders build(UpstreamRequestContext context, String normalizedBody) {
        var headers = new UpstreamHeaders();
        headers.set(OpenAiCodexProfile.HEADER_AUTHORIZATION, OpenAiCodexProfile.bearerToken(context.accessToken()));
        headers.set(OpenAiCodexProfile.HEADER_CONTENT_TYPE, OpenAiCodexProfile.CONTENT_TYPE_JSON);

        UpstreamRoute route = context.upstreamRoute();
        if (route == null) {
            return headers;
        }
        if (route.normalizeCodexOAuthBody()) {
            appendCodexOAuthHeaders(headers, context, normalizedBody, route);
        } else if (route.endpointKind() == EndpointKind.OPENAI_RESPONSES) {
            appendOpenAiResponsesHeaders(headers, context, normalizedBody, route);
        } else if (route.endpointKind() == EndpointKind.OPENAI_CHAT_COMPLETIONS) {
            appendOpenAiRawChatHeaders(headers, context);
        }
        return headers;
    }

    private static void appendCodexOAuthHeaders(UpstreamHeaders headers, UpstreamRequestContext context,
                                                String normalizedBody, UpstreamRoute route) {
        var requestHeaders = context.requestHeaders();
        String chatgptAccountId = credentialText(context.account(), OpenAiCodexProfile.CREDENTIAL_CHATGPT_ACCOUNT_ID);
        headers.set(OpenAiCodexProfile.HEADER_CHATGPT_ACCOUNT_ID, chatgptAccountId);
        headers.copyAllowed(requestHeaders, OpenAiHeaderPolicy.CODEX_OAUTH_ALLOWED_HEADERS);

        headers.set(OpenAiCodexProfile.HEADER_CONTENT_TYPE, OpenAiCodexProfile.CONTENT_TYPE_JSON);
        headers.remove(OpenAiCodexProfile.HEADER_CONVERSATION_ID);
        headers.remove(OpenAiCodexProfile.HEADER_SESSION_ID);

        String promptCacheKey = firstNonBlank(
                extractPromptCacheKey(normalizedBody),
                extractPromptCacheKey(context.body()));
        if (isAnthropicMessagesCompat(route) && !promptCacheKey.isBlank()) {
            String isolatedSessionId = OpenAiSessionIdPolicy.compatSessionUuid(context.apiKeyId(), promptCacheKey);
            headers.set(OpenAiCodexProfile.HEADER_SESSION_ID, isolatedSessionId);
            if (!GatewayHeaderPolicy.value(requestHeaders, OpenAiCodexProfile.HEADER_CONVERSATION_ID).isBlank()) {
                headers.set(OpenAiCodexProfile.HEADER_CONVERSATION_ID, isolatedSessionId);
            } else {
                headers.remove(OpenAiCodexProfile.HEADER_CONVERSATION_ID);
            }
        } else {
            String clientSessionId = firstNonBlank(
                    GatewayHeaderPolicy.value(requestHeaders, OpenAiCodexProfile.HEADER_SESSION_ID), promptCacheKey);
            String clientConversationId = firstNonBlank(
                    GatewayHeaderPolicy.value(requestHeaders, OpenAiCodexProfile.HEADER_CONVERSATION_ID),
                    promptCacheKey);
            if (!clientSessionId.isBlank()) {
                headers.set(OpenAiCodexProfile.HEADER_SESSION_ID,
                        OpenAiSessionIdPolicy.isolateSessionId(context.apiKeyId(), clientSessionId));
            }
            if (!clientConversationId.isBlank()) {
                headers.set(OpenAiCodexProfile.HEADER_CONVERSATION_ID,
                        OpenAiSessionIdPolicy.isolateSessionId(context.apiKeyId(), clientConversationId));
            }
        }
        if (route.forceNonStreamingResponse()) {
            headers.set(OpenAiCodexProfile.HEADER_ACCEPT, OpenAiCodexProfile.ACCEPT_JSON);
            headers.setIfAbsent(OpenAiCodexProfile.HEADER_VERSION, OpenAiCodexProfile.CLI_VERSION);
        } else {
            headers.set(OpenAiCodexProfile.HEADER_ACCEPT, OpenAiCodexProfile.ACCEPT_EVENT_STREAM);
        }
        if (isAnthropicMessagesCompat(route)) {
            headers.remove(OpenAiCodexProfile.HEADER_OPENAI_BETA);
            headers.remove(OpenAiCodexProfile.HEADER_ORIGINATOR);
        } else {
            headers.setIfAbsent(OpenAiCodexProfile.HEADER_OPENAI_BETA,
                    OpenAiCodexProfile.OPENAI_BETA_RESPONSES_EXPERIMENTAL);
            headers.setIfAbsent(OpenAiCodexProfile.HEADER_ORIGINATOR,
                    OpenAiCodexProfile.ORIGINATOR_CODEX_CLI_RS);
        }
        if (!OpenAiCodexProfile.isCodexCliUserAgent(headers.get(OpenAiCodexProfile.HEADER_USER_AGENT))) {
            headers.set(OpenAiCodexProfile.HEADER_USER_AGENT, OpenAiCodexProfile.CLI_USER_AGENT);
        }
    }

    private static void appendOpenAiRawChatHeaders(UpstreamHeaders headers, UpstreamRequestContext context) {
        headers.set(OpenAiCodexProfile.HEADER_ACCEPT,
                context.stream() ? OpenAiCodexProfile.ACCEPT_EVENT_STREAM : OpenAiCodexProfile.ACCEPT_JSON);
        headers.copyAllowed(context.requestHeaders(), OpenAiHeaderPolicy.RAW_CHAT_ALLOWED_HEADERS);
        applyOpenAiApiKeyUserAgentOverride(headers, context.account());
    }

    private static void appendOpenAiResponsesHeaders(UpstreamHeaders headers,
                                                     UpstreamRequestContext context,
                                                     String normalizedBody,
                                                     UpstreamRoute route) {
        headers.copyAllowed(context.requestHeaders(), OpenAiHeaderPolicy.API_KEY_RESPONSES_ALLOWED_HEADERS);
        headers.setIfAbsent(OpenAiCodexProfile.HEADER_ACCEPT,
                context.stream() ? OpenAiCodexProfile.ACCEPT_EVENT_STREAM : OpenAiCodexProfile.ACCEPT_JSON);
        String promptCacheKey = firstNonBlank(
                extractPromptCacheKey(normalizedBody),
                extractPromptCacheKey(context.body()));
        if (isAnthropicMessagesCompat(route) && !promptCacheKey.isBlank()) {
            String isolatedSessionId = OpenAiSessionIdPolicy.compatSessionUuid(context.apiKeyId(), promptCacheKey);
            headers.set(OpenAiCodexProfile.HEADER_SESSION_ID, isolatedSessionId);
            if (!GatewayHeaderPolicy.value(context.requestHeaders(), OpenAiCodexProfile.HEADER_CONVERSATION_ID).isBlank()) {
                headers.set(OpenAiCodexProfile.HEADER_CONVERSATION_ID, isolatedSessionId);
            }
        }
        applyOpenAiApiKeyUserAgentOverride(headers, context.account());
    }

    private static void applyOpenAiApiKeyUserAgentOverride(UpstreamHeaders headers, AccountEntity account) {
        String userAgent = credentialText(account, OpenAiCodexProfile.CREDENTIAL_USER_AGENT);
        if (!userAgent.isBlank()) {
            headers.set(OpenAiCodexProfile.HEADER_USER_AGENT, userAgent);
        }
    }

    private static String credentialText(AccountEntity account, String field) {
        if (account == null || account.getCredentials() == null || account.getCredentials().isBlank()
                || field == null || field.isBlank()) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(account.getCredentials());
            JsonNode value = root.get(field);
            return value != null && value.isTextual() ? value.asText().trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String extractPromptCacheKey(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode value = root.get(OpenAiCodexProfile.FIELD_PROMPT_CACHE_KEY);
            return value != null && value.isTextual() ? value.asText().trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static boolean isAnthropicMessagesCompat(UpstreamRoute route) {
        return route != null && GatewayProtocolFormat.MESSAGES.is(route.clientFormat());
    }
}
