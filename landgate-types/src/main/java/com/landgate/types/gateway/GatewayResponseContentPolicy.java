package com.landgate.types.gateway;

/**
 * Stable gateway response content-type facts and pure helpers.
 *
 * <p>This type must not read HTTP responses, write servlet responses, select
 * routes, translate protocols, parse SSE, or calculate usage.</p>
 */
public final class GatewayResponseContentPolicy {

    public static final String MEDIA_TYPE_EVENT_STREAM = "text/event-stream";
    public static final String MEDIA_TYPE_JSON = "application/json";
    public static final String CHARSET_UTF_8 = "UTF-8";

    private GatewayResponseContentPolicy() {
    }

    public static boolean isEventStream(String contentType) {
        return contentType != null && contentType.toLowerCase().contains(MEDIA_TYPE_EVENT_STREAM);
    }
}
