package com.landgate.types.gateway;

import java.io.InputStream;
import java.net.http.HttpResponse;

/**
 * Decides how a successful upstream response should be handled.
 *
 * <p>This policy only looks at route/client stream intent and upstream response
 * headers. It must not read response bodies, translate protocols, parse usage,
 * write servlet responses, or calculate billing.</p>
 */
public final class GatewayResponseHandlingPolicy {

    public static final long STREAMING_CONCURRENCY_LEASE_RENEWAL_INTERVAL_MILLIS = 60_000L;

    private GatewayResponseHandlingPolicy() {
    }

    public static boolean shouldHandleAsStreaming(boolean upstreamStreamRequested,
                                                  HttpResponse<InputStream> upstreamResp) {
        if (upstreamStreamRequested) {
            return true;
        }
        if (upstreamResp == null || upstreamResp.headers() == null) {
            return false;
        }
        return upstreamResp.headers().firstValue(GatewayResponseHeaderPolicy.HEADER_CONTENT_TYPE)
                .map(GatewayResponseContentPolicy::isEventStream)
                .orElse(false);
    }
}
