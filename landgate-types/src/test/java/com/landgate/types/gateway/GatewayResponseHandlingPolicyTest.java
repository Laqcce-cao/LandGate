package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Gateway response handling policy tests")
class GatewayResponseHandlingPolicyTest {

    @Test
    @DisplayName("Explicit upstream stream intent always handles as streaming")
    void explicitStreamIntentWins() {
        assertTrue(GatewayResponseHandlingPolicy.shouldHandleAsStreaming(true, null));
    }

    @Test
    @DisplayName("Event-stream content type is detected when stream intent is absent")
    void eventStreamContentTypeIsStreaming() {
        HttpResponse<InputStream> response = responseWithContentType("text/event-stream; charset=utf-8");

        assertTrue(GatewayResponseHandlingPolicy.shouldHandleAsStreaming(false, response));
    }

    @Test
    @DisplayName("JSON or missing upstream response is not treated as streaming")
    void jsonOrMissingResponseIsNotStreaming() {
        assertFalse(GatewayResponseHandlingPolicy.shouldHandleAsStreaming(false, null));
        assertFalse(GatewayResponseHandlingPolicy.shouldHandleAsStreaming(false,
                responseWithContentType("application/json")));
    }

    @Test
    @DisplayName("Streaming lease renewal interval is centralized")
    void streamingLeaseRenewalIntervalIsCentralized() {
        assertEquals(60_000L, GatewayResponseHandlingPolicy.STREAMING_CONCURRENCY_LEASE_RENEWAL_INTERVAL_MILLIS);
    }

    private static HttpResponse<InputStream> responseWithContentType(String contentType) {
        HttpHeaders headers = HttpHeaders.of(
                Map.of(GatewayResponseHeaderPolicy.HEADER_CONTENT_TYPE, List.of(contentType)),
                (name, value) -> true);
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public HttpRequest request() {
                return null;
            }

            @Override
            public Optional<HttpResponse<InputStream>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return headers;
            }

            @Override
            public InputStream body() {
                return InputStream.nullInputStream();
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create("https://example.test");
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }
}
