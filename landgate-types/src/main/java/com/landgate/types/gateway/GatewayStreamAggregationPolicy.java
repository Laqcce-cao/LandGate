package com.landgate.types.gateway;

/**
 * Route-level policy for upstream SSE -> client non-streaming aggregation.
 *
 * <p>This class owns only the fallback decision when the upstream SSE stream
 * does not contain a terminal Responses event. It must not read HTTP streams,
 * write servlet responses, translate protocols, or parse usage.</p>
 */
public final class GatewayStreamAggregationPolicy {

    public static final int PROTOCOL_ERROR_STATUS = 502;
    public static final String PROTOCOL_ERROR_TYPE = "upstream_error";
    public static final String MISSING_TERMINAL_MESSAGE = "stream usage incomplete: missing terminal event";
    public static final String FAILED_TERMINAL_FALLBACK_MESSAGE = "Upstream compact response failed";
    public static final String INVALID_NON_STREAMING_RESPONSE_MESSAGE = "Upstream returned an invalid non-streaming response";

    private GatewayStreamAggregationPolicy() {
    }

    public static MissingTerminalAction missingTerminalAction(String clientFormat) {
        if (isResponsesClient(clientFormat)) {
            return MissingTerminalAction.PRESERVE_UPSTREAM_SSE;
        }
        return MissingTerminalAction.PROTOCOL_ERROR;
    }

    public static boolean isResponsesClient(String clientFormat) {
        return GatewayProtocolFormat.RESPONSES.is(clientFormat);
    }

    public enum MissingTerminalAction {
        PRESERVE_UPSTREAM_SSE,
        PROTOCOL_ERROR
    }
}
