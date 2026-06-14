package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UpstreamStreamRequestNormalizer tests")
class UpstreamStreamRequestNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("forced OpenAI Responses upstream streaming sets body stream true")
    void forcedOpenAiResponsesUpstreamStreamingSetsBodyStreamTrue() throws Exception {
        String normalized = UpstreamStreamRequestNormalizer.normalize(
                "{\"model\":\"gpt-5.5\",\"stream\":false,\"input\":\"Hi\"}",
                route(EndpointKind.OPENAI_RESPONSES, "messages", "responses",
                        "https://api.openai.com/v1/responses"),
                true);

        JsonNode root = JSON.readTree(normalized);

        assertTrue(root.get(OpenAiResponsesBodyPolicy.FIELD_STREAM).asBoolean());
    }

    @Test
    @DisplayName("non-Responses upstream body is not mutated")
    void nonResponsesUpstreamBodyIsNotMutated() {
        String body = "{\"model\":\"gpt-5.5\",\"stream\":false,\"messages\":[]}";

        String normalized = UpstreamStreamRequestNormalizer.normalize(
                body,
                route(EndpointKind.OPENAI_CHAT_COMPLETIONS, "messages", "chat_completions",
                        "https://api.openai.com/v1/chat/completions"),
                true);

        assertEquals(body, normalized);
    }

    @Test
    @DisplayName("forced non-streaming endpoint body is not mutated")
    void forcedNonStreamingEndpointBodyIsNotMutated() {
        String body = "{\"model\":\"gpt-5.5\",\"stream\":true,\"input\":\"Compact\"}";

        String normalized = UpstreamStreamRequestNormalizer.normalize(
                body,
                route(EndpointKind.OPENAI_RESPONSES, "responses", "responses",
                        "https://api.openai.com/v1/responses/compact"),
                true);

        assertEquals(body, normalized);
    }

    private static UpstreamRoute route(EndpointKind endpointKind,
                                       String clientFormat,
                                       String upstreamFormat,
                                       String targetUrl) {
        return new UpstreamRoute(
                Platform.OPENAI,
                clientFormat,
                upstreamFormat,
                endpointKind,
                targetUrl,
                true,
                false,
                upstreamFormat,
                "test");
    }
}
