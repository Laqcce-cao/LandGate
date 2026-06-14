package com.landgate.types.gateway;

import java.util.UUID;

/**
 * Stable tool-call ID prefix policy shared by protocol converters.
 *
 * <p>This type owns prefix facts and tiny ID normalization helpers only. It must
 * not parse request bodies, translate protocol payloads, perform auth, select
 * routes, or build HTTP requests.</p>
 */
public final class GatewayToolCallIdPolicy {

    public static final String OPENAI_RESPONSES_FUNCTION_CALL_PREFIX = "fc_";
    public static final String OPENAI_CHAT_TOOL_CALL_PREFIX = "call_";
    public static final String ANTHROPIC_TOOL_USE_PREFIX = "toolu_";

    private GatewayToolCallIdPolicy() {
    }

    public static String fromResponsesCallIdForAnthropicResponse(String callId) {
        if (callId == null) return "";
        String withoutResponsesPrefix = stripResponsesFunctionCallPrefix(callId);
        return hasNativeToolCallPrefix(withoutResponsesPrefix) ? withoutResponsesPrefix : callId;
    }

    public static String fromResponsesCallIdForAnthropicRequest(String callId) {
        if (callId == null) return ANTHROPIC_TOOL_USE_PREFIX + UUID.randomUUID();
        String withoutResponsesPrefix = stripResponsesFunctionCallPrefix(callId);
        if (hasNativeToolCallPrefix(withoutResponsesPrefix)) {
            return withoutResponsesPrefix;
        }
        return ANTHROPIC_TOOL_USE_PREFIX + callId;
    }

    public static String toResponsesCallIdFromAnthropic(String anthropicToolUseId) {
        return anthropicToolUseId == null ? "" : anthropicToolUseId;
    }

    public static boolean hasNativeToolCallPrefix(String callId) {
        return callId != null
                && (callId.startsWith(ANTHROPIC_TOOL_USE_PREFIX)
                || callId.startsWith(OPENAI_CHAT_TOOL_CALL_PREFIX));
    }

    public static String stripResponsesFunctionCallPrefix(String callId) {
        if (callId != null && callId.startsWith(OPENAI_RESPONSES_FUNCTION_CALL_PREFIX)) {
            return callId.substring(OPENAI_RESPONSES_FUNCTION_CALL_PREFIX.length());
        }
        return callId;
    }
}
