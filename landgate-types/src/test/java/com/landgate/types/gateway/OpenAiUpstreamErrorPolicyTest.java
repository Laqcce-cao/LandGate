package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenAI upstream error policy tests")
class OpenAiUpstreamErrorPolicyTest {

    @Test
    @DisplayName("Sub2API OpenAI failover status set is locked")
    void failoverStatusSet() {
        assertTrue(OpenAiUpstreamErrorPolicy.isFailoverStatus(401));
        assertTrue(OpenAiUpstreamErrorPolicy.isFailoverStatus(402));
        assertTrue(OpenAiUpstreamErrorPolicy.isFailoverStatus(403));
        assertTrue(OpenAiUpstreamErrorPolicy.isFailoverStatus(429));
        assertTrue(OpenAiUpstreamErrorPolicy.isFailoverStatus(529));
        assertTrue(OpenAiUpstreamErrorPolicy.isFailoverStatus(500));
        assertTrue(OpenAiUpstreamErrorPolicy.isFailoverStatus(503));

        assertFalse(OpenAiUpstreamErrorPolicy.isFailoverStatus(400));
        assertFalse(OpenAiUpstreamErrorPolicy.isFailoverStatus(404));
        assertFalse(OpenAiUpstreamErrorPolicy.isFailoverStatus(422));
    }

    @Test
    @DisplayName("Transient OpenAI 400 remains failover eligible through unified policy")
    void transientProcessingError() {
        assertTrue(OpenAiUpstreamErrorPolicy.shouldFailover(
                400,
                "An error occurred while processing your request.",
                null));
        assertFalse(OpenAiUpstreamErrorPolicy.shouldFailover(
                400,
                "Missing required parameter: 'instructions'",
                "{\"error\":{\"message\":\"Missing required parameter: 'instructions'\"}}"));
    }

    @Test
    @DisplayName("OpenAI 402/403 account-state messages are stable policy facts")
    void accountStateMessages() {
        assertEquals("Payment required (402): billing failed",
                OpenAiUpstreamErrorPolicy.paymentRequiredAccountError("billing failed"));
        assertEquals("Workspace deactivated (402): workspace has been deactivated",
                OpenAiUpstreamErrorPolicy.paymentRequiredAccountError(
                        "{\"detail\":{\"code\":\"deactivated_workspace\"}}", "billing failed"));
        assertEquals("Payment required (402): insufficient balance or billing issue",
                OpenAiUpstreamErrorPolicy.paymentRequiredAccountError(""));
        assertEquals("Access forbidden (403): temporary edge rejection",
                OpenAiUpstreamErrorPolicy.forbiddenAccountError("temporary edge rejection"));
        assertEquals("OpenAI 403 temporary cooldown (1/3): Access forbidden (403): temporary edge rejection",
                OpenAiUpstreamErrorPolicy.forbiddenTempUnschedulableReason(1, "temporary edge rejection"));
        assertEquals("Access forbidden (403): temporary edge rejection | consecutive_403=3/3",
                OpenAiUpstreamErrorPolicy.forbiddenThresholdAccountError("temporary edge rejection", 3));
        assertEquals(600, OpenAiUpstreamErrorPolicy.FORBIDDEN_TEMP_UNSCHEDULABLE_SECONDS);
        assertEquals(3, OpenAiUpstreamErrorPolicy.FORBIDDEN_DISABLE_THRESHOLD);
        assertEquals(180, OpenAiUpstreamErrorPolicy.FORBIDDEN_COUNTER_WINDOW_MINUTES);
    }

    @Test
    @DisplayName("OpenAI permanent 401 auth failures match Sub2API token revoked policy")
    void permanentUnauthorized() {
        String revoked = "{\"error\":{\"code\":\"token_revoked\",\"message\":\"revoked upstream\"}}";
        String invalidatedInMessage = """
                {"error":{"message":"{\\"error\\":{\\"code\\":\\"token_invalidated\\",\\"message\\":\\"gone\\"}}"}}
                """;
        String detailUnauthorized = "{\"detail\":\"Unauthorized\"}";
        String normalExpired = "{\"error\":{\"message\":\"expired token\"}}";

        assertTrue(OpenAiUpstreamErrorPolicy.isPermanentUnauthorized(401, revoked));
        assertTrue(OpenAiUpstreamErrorPolicy.isPermanentUnauthorized(401, invalidatedInMessage));
        assertTrue(OpenAiUpstreamErrorPolicy.isPermanentUnauthorized(401, detailUnauthorized));
        assertFalse(OpenAiUpstreamErrorPolicy.isPermanentUnauthorized(401, normalExpired));
        assertFalse(OpenAiUpstreamErrorPolicy.isPermanentUnauthorized(400, revoked));

        assertEquals("Token revoked (401): revoked upstream",
                OpenAiUpstreamErrorPolicy.permanentUnauthorizedAccountError(revoked, "revoked upstream"));
        assertEquals("Unauthorized (401): account authentication failed permanently",
                OpenAiUpstreamErrorPolicy.permanentUnauthorizedAccountError(detailUnauthorized, ""));
        assertEquals("OAuth 401: expired upstream",
                OpenAiUpstreamErrorPolicy.oauthUnauthorizedTempUnschedulableReason("expired upstream"));
        assertEquals("Authentication failed (401): invalid or expired credentials",
                OpenAiUpstreamErrorPolicy.oauthUnauthorizedTempUnschedulableReason(""));
        assertEquals(600, OpenAiUpstreamErrorPolicy.OAUTH_UNAUTHORIZED_TEMP_UNSCHEDULABLE_SECONDS);
    }
}
