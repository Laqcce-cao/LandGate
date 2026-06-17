package com.landgate.types.gateway;

/**
 * Sub2API-compatible client-facing HTTP Anthropic Messages validation facts.
 *
 * <p>This policy owns only stable status/code/message facts. It must not parse
 * JSON, mutate request bodies, choose routes, build auth, or write responses.</p>
 */
public final class AnthropicMessagesHttpRequestPolicy {

    public static final int STATUS_BAD_REQUEST = OpenAiResponsesHttpRequestPolicy.STATUS_BAD_REQUEST;
    public static final String ERROR_CODE_INVALID_REQUEST = OpenAiResponsesHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST;

    public static final String MESSAGE_EMPTY_BODY = OpenAiResponsesHttpRequestPolicy.MESSAGE_EMPTY_BODY;
    public static final String MESSAGE_PARSE_BODY_FAILED = OpenAiResponsesHttpRequestPolicy.MESSAGE_PARSE_BODY_FAILED;
    public static final String MESSAGE_MODEL_REQUIRED = OpenAiResponsesHttpRequestPolicy.MESSAGE_MODEL_REQUIRED;
    public static final String MESSAGE_INVALID_STREAM_TYPE = OpenAiResponsesHttpRequestPolicy.MESSAGE_INVALID_STREAM_TYPE;

    private AnthropicMessagesHttpRequestPolicy() {
    }

    public static boolean appliesToClientFormat(String requestFormat) {
        return GatewayProtocolFormat.MESSAGES.is(requestFormat);
    }

    public static boolean isNativeMessagesRoute(String requestFormat, String upstreamFormat) {
        return appliesToClientFormat(requestFormat)
                && GatewayProtocolFormat.MESSAGES.is(upstreamFormat);
    }
}
