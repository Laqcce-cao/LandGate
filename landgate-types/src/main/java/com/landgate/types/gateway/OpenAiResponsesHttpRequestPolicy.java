package com.landgate.types.gateway;

/**
 * Sub2API-compatible client-facing HTTP Responses validation facts.
 *
 * <p>This policy owns only stable status/code/message facts. It must not parse
 * JSON, mutate request bodies, choose routes, build auth, or write responses.</p>
 */
public final class OpenAiResponsesHttpRequestPolicy {

    public static final int STATUS_BAD_REQUEST = 400;
    public static final String ERROR_CODE_INVALID_REQUEST = "invalid_request_error";

    public static final String MESSAGE_EMPTY_BODY = "Request body is empty";
    public static final String MESSAGE_PARSE_BODY_FAILED = "Failed to parse request body";
    public static final String MESSAGE_MODEL_REQUIRED = "model is required";
    public static final String MESSAGE_INVALID_STREAM_TYPE = "invalid stream field type";
    public static final String MESSAGE_FUNCTION_CALL_OUTPUT_REQUIRES_CALL_ID =
            "function_call_output requires call_id on HTTP requests; continuation via previous_response_id is only supported on Responses WebSocket v2";
    public static final String MESSAGE_FUNCTION_CALL_OUTPUT_REQUIRES_ITEM_REFERENCE =
            "function_call_output requires item_reference ids matching each call_id on HTTP requests; continuation via previous_response_id is only supported on Responses WebSocket v2";

    private OpenAiResponsesHttpRequestPolicy() {
    }

    public static boolean appliesToClientFormat(String requestFormat) {
        return GatewayProtocolFormat.RESPONSES.is(requestFormat);
    }
}
