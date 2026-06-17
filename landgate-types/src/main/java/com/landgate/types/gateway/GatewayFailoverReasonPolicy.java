package com.landgate.types.gateway;

/**
 * Stable internal failover reason identifiers.
 *
 * <p>This policy owns only diagnostic reason strings used when excluding a
 * selected account from the current request's failover candidates. It must not
 * select accounts, mark health, write responses, or decide whether to fail over.</p>
 */
public final class GatewayFailoverReasonPolicy {

    public static final String STICKY_MODEL_UNSUPPORTED = "sticky_model_unsupported";
    public static final String OPENAI_COMPACT_UNSUPPORTED = "openai_compact_unsupported";
    public static final String CONCURRENCY_UNAVAILABLE = "concurrency_unavailable";
    public static final String ACCESS_TOKEN_UNAVAILABLE = "access_token_unavailable";
    public static final String BUILD_UPSTREAM_REQUEST_FAILED = "build_upstream_request_failed";
    public static final String UPSTREAM_IO_ERROR = "upstream_io_error";

    private static final String RETRYABLE_UPSTREAM_PREFIX = "retryable_upstream_";
    private static final String PASSTHROUGH_RETRY_PREFIX = "passthrough_retry_";

    private GatewayFailoverReasonPolicy() {
    }

    public static String retryableUpstream(int statusCode) {
        return RETRYABLE_UPSTREAM_PREFIX + statusCode;
    }

    public static String passthroughRetry(int statusCode) {
        return PASSTHROUGH_RETRY_PREFIX + statusCode;
    }
}
