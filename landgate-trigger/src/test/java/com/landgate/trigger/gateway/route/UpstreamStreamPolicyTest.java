package com.landgate.trigger.gateway.route;

import com.landgate.types.gateway.GatewayProtocolFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("UpstreamStreamPolicy tests")
class UpstreamStreamPolicyTest {

    @Test
    @DisplayName("OpenAI API Key Responses forces upstream stream only for translated clients")
    void openAiApiKeyResponsesStreamingOnlyForTranslatedClients() {
        assertTrue(UpstreamStreamPolicy.forceOpenAiApiKeyResponsesStreaming(
                GatewayProtocolFormat.MESSAGES.id(), GatewayProtocolFormat.RESPONSES.id()));
        assertTrue(UpstreamStreamPolicy.forceOpenAiApiKeyResponsesStreaming(
                GatewayProtocolFormat.CHAT_COMPLETIONS.id(), GatewayProtocolFormat.RESPONSES.id()));

        assertFalse(UpstreamStreamPolicy.forceOpenAiApiKeyResponsesStreaming(
                GatewayProtocolFormat.RESPONSES.id(), GatewayProtocolFormat.RESPONSES.id()));
        assertFalse(UpstreamStreamPolicy.forceOpenAiApiKeyResponsesStreaming(
                GatewayProtocolFormat.MESSAGES.id(), GatewayProtocolFormat.CHAT_COMPLETIONS.id()));
    }

    @Test
    @DisplayName("OpenAI OAuth Codex forces SSE except compact JSON endpoints")
    void openAiOauthCodexStreamingExceptCompact() {
        assertTrue(UpstreamStreamPolicy.forceOpenAiOAuthCodexStreaming(
                EndpointKind.OPENAI_CODEX_RESPONSES,
                "https://chatgpt.com/backend-api/codex/responses"));

        assertFalse(UpstreamStreamPolicy.forceOpenAiOAuthCodexStreaming(
                EndpointKind.OPENAI_CODEX_RESPONSES,
                "https://chatgpt.com/backend-api/codex/responses/compact"));
    }

    @Test
    @DisplayName("Responses compact endpoints force non-streaming responses")
    void compactResponsesForceNonStreamingResponse() {
        assertTrue(UpstreamStreamPolicy.forceNonStreamingResponse(
                EndpointKind.OPENAI_RESPONSES,
                "https://api.openai.com/v1/responses/compact"));
        assertTrue(UpstreamStreamPolicy.forceNonStreamingResponse(
                EndpointKind.OPENAI_CODEX_RESPONSES,
                "https://chatgpt.com/backend-api/codex/responses/compact/"));

        assertFalse(UpstreamStreamPolicy.forceNonStreamingResponse(
                EndpointKind.OPENAI_CHAT_COMPLETIONS,
                "https://api.openai.com/v1/chat/completions"));
        assertFalse(UpstreamStreamPolicy.forceNonStreamingResponse(
                EndpointKind.OPENAI_RESPONSES,
                "https://api.openai.com/v1/responses"));
    }
}
