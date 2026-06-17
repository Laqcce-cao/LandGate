package com.landgate.types.gateway;

import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("GatewayFailoverExhaustionPolicy tests")
class GatewayFailoverExhaustionPolicyTest {

    @Test
    @DisplayName("messages over OpenAI uses Sub2API OpenAI failover mapping")
    void messagesOverOpenAiUsesOpenAiMapping() {
        GatewayFailoverExhaustionPolicy.Decision decision =
                GatewayFailoverExhaustionPolicy.decide("messages", Platform.OPENAI, 529);

        assertEquals(503, decision.status());
        assertEquals("upstream_error", decision.code());
        assertEquals("Upstream service overloaded, please retry later", decision.message());
    }

    @Test
    @DisplayName("messages over Anthropic keeps Anthropic overloaded error type")
    void messagesOverAnthropicUsesAnthropicMapping() {
        GatewayFailoverExhaustionPolicy.Decision decision =
                GatewayFailoverExhaustionPolicy.decide("messages", Platform.ANTHROPIC, 529);

        assertEquals(503, decision.status());
        assertEquals("overloaded_error", decision.code());
        assertEquals("Upstream service overloaded, please retry later", decision.message());
    }

    @Test
    @DisplayName("responses/chat over Anthropic preserve upstream status and server_error exhausted envelope")
    void responsesAndChatOverAnthropicUseForwardAsExhaustedEnvelope() {
        GatewayFailoverExhaustionPolicy.Decision responses =
                GatewayFailoverExhaustionPolicy.decide("responses", Platform.ANTHROPIC, 429);
        GatewayFailoverExhaustionPolicy.Decision chat =
                GatewayFailoverExhaustionPolicy.decide("chat_completions", Platform.ANTHROPIC, 503);

        assertEquals(429, responses.status());
        assertEquals("server_error", responses.code());
        assertEquals("All available accounts exhausted", responses.message());
        assertEquals(503, chat.status());
        assertEquals("server_error", chat.code());
        assertEquals("All available accounts exhausted", chat.message());
    }

    @Test
    @DisplayName("responses/chat over OpenAI use OpenAI failover mapping")
    void responsesAndChatOverOpenAiUseOpenAiMapping() {
        GatewayFailoverExhaustionPolicy.Decision rateLimit =
                GatewayFailoverExhaustionPolicy.decide("responses", Platform.OPENAI, 429);
        GatewayFailoverExhaustionPolicy.Decision serverError =
                GatewayFailoverExhaustionPolicy.decide("chat_completions", Platform.OPENAI, 500);

        assertEquals(429, rateLimit.status());
        assertEquals("rate_limit_error", rateLimit.code());
        assertEquals("Upstream rate limit exceeded, please retry later", rateLimit.message());
        assertEquals(502, serverError.status());
        assertEquals("upstream_error", serverError.code());
        assertEquals("Upstream service temporarily unavailable", serverError.message());
    }

    @Test
    @DisplayName("fallback no-account errors are centralized")
    void fallbackNoAccountErrorsAreCentralized() {
        GatewayFailoverExhaustionPolicy.Decision noAvailable =
                GatewayFailoverExhaustionPolicy.noAvailableAccounts("core");
        GatewayFailoverExhaustionPolicy.Decision exhausted =
                GatewayFailoverExhaustionPolicy.exhaustedWithoutUpstreamError("core", 3);

        assertEquals(503, noAvailable.status());
        assertEquals("overloaded_error", noAvailable.code());
        assertEquals("No available accounts in group 'core'.", noAvailable.message());
        assertEquals(503, exhausted.status());
        assertEquals("overloaded_error", exhausted.code());
        assertEquals("All accounts in group 'core' are unavailable after 3 attempts.", exhausted.message());
    }
}
