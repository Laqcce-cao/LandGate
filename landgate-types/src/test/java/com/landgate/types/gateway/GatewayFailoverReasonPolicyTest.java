package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Gateway failover reason policy tests")
class GatewayFailoverReasonPolicyTest {

    @Test
    @DisplayName("static failover reasons are stable")
    void staticReasons() {
        assertEquals("sticky_model_unsupported", GatewayFailoverReasonPolicy.STICKY_MODEL_UNSUPPORTED);
        assertEquals("openai_compact_unsupported", GatewayFailoverReasonPolicy.OPENAI_COMPACT_UNSUPPORTED);
        assertEquals("concurrency_unavailable", GatewayFailoverReasonPolicy.CONCURRENCY_UNAVAILABLE);
        assertEquals("access_token_unavailable", GatewayFailoverReasonPolicy.ACCESS_TOKEN_UNAVAILABLE);
        assertEquals("build_upstream_request_failed", GatewayFailoverReasonPolicy.BUILD_UPSTREAM_REQUEST_FAILED);
        assertEquals("upstream_io_error", GatewayFailoverReasonPolicy.UPSTREAM_IO_ERROR);
    }

    @Test
    @DisplayName("dynamic upstream retry reasons include status code")
    void dynamicReasons() {
        assertEquals("retryable_upstream_503", GatewayFailoverReasonPolicy.retryableUpstream(503));
        assertEquals("passthrough_retry_529", GatewayFailoverReasonPolicy.passthroughRetry(529));
    }
}
