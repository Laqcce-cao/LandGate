package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Gateway error response policy tests")
class ErrorResponsePolicyTest {

    @Test
    @DisplayName("HTTP status codes map to protocol-visible error type codes")
    void statusMapsToErrorCode() {
        assertEquals("invalid_request_error", ErrorResponsePolicy.errorCodeForStatus(400));
        assertEquals("authentication_error", ErrorResponsePolicy.errorCodeForStatus(401));
        assertEquals("insufficient_quota", ErrorResponsePolicy.errorCodeForStatus(402));
        assertEquals("permission_error", ErrorResponsePolicy.errorCodeForStatus(403));
        assertEquals("not_found_error", ErrorResponsePolicy.errorCodeForStatus(404));
        assertEquals("unprocessable_error", ErrorResponsePolicy.errorCodeForStatus(422));
        assertEquals("rate_limit_error", ErrorResponsePolicy.errorCodeForStatus(429));
        assertEquals("overloaded_error", ErrorResponsePolicy.errorCodeForStatus(529));
        assertEquals("upstream_error", ErrorResponsePolicy.errorCodeForStatus(418));
    }

    @Test
    @DisplayName("Safe messages extract normal request errors but hide account/quota failures")
    void safeMessageExtraction() {
        assertEquals("bad param",
                ErrorResponsePolicy.safeMessageForStatus(400, "{\"error\":{\"message\":\"bad param\"}}"));
        assertEquals("The service is temporarily unavailable. Please try again later.",
                ErrorResponsePolicy.safeMessageForStatus(402, "{\"error\":{\"message\":\"billing failed\"}}"));
        assertEquals("Too many requests. Please slow down.",
                ErrorResponsePolicy.safeMessageForStatus(429, "{\"error\":{\"message\":\"quota exceeded\"}}"));
        assertEquals("An upstream error occurred. Status: 418",
                ErrorResponsePolicy.safeMessageForStatus(418, "{}"));
    }

    @Test
    @DisplayName("Safe messages are truncated before entering client error envelopes")
    void safeMessageTruncatesLongMessages() {
        String longMessage = "x".repeat(240);
        String safe = ErrorResponsePolicy.safeMessageForStatus(400,
                "{\"error\":{\"message\":\"" + longMessage + "\"}}");

        assertEquals(203, safe.length());
        assertEquals("...", safe.substring(200));
    }
}
