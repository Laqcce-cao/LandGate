package com.landgate.types.gateway;

/**
 * Stable no-usage audit reason formatting for successful upstream responses
 * that cannot be settled as normal usage.
 *
 * <p>This policy owns only reason keys and formatting. It must not parse usage,
 * read HTTP streams, write billing records, or decide whether a response is
 * successful.</p>
 */
public final class GatewayNoUsageAuditPolicy {

    public static final String REASON_PROTOCOL_ERROR = "protocol_error";
    public static final String REASON_USAGE_NOT_PARSED = "usage_not_parsed";

    private GatewayNoUsageAuditPolicy() {
    }

    public static String protocolErrorReason(String endpoint,
                                             String parser,
                                             boolean clientStream,
                                             boolean upstreamStream,
                                             boolean handledAsStream,
                                             String message) {
        return baseReason(REASON_PROTOCOL_ERROR, endpoint, parser, clientStream, upstreamStream, handledAsStream)
                + "; message=" + safe(message);
    }

    public static String usageNotParsedReason(String endpoint,
                                              String parser,
                                              boolean clientStream,
                                              boolean upstreamStream,
                                              boolean handledAsStream,
                                              String contentType) {
        return baseReason(REASON_USAGE_NOT_PARSED, endpoint, parser, clientStream, upstreamStream, handledAsStream)
                + "; content_type=" + safe(contentType);
    }

    private static String baseReason(String reason,
                                     String endpoint,
                                     String parser,
                                     boolean clientStream,
                                     boolean upstreamStream,
                                     boolean handledAsStream) {
        return reason
                + "; endpoint=" + safe(endpoint)
                + "; parser=" + safe(parser)
                + "; client_stream=" + clientStream
                + "; upstream_stream=" + upstreamStream
                + "; handled_as_stream=" + handledAsStream;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
