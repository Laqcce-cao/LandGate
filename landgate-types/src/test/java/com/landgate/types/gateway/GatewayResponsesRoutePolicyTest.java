package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GatewayResponsesRoutePolicy 测试")
class GatewayResponsesRoutePolicyTest {

    @Test
    @DisplayName("客户端 Responses 路径规范化为 dispatcher upstream path")
    void canonicalizesClientUpstreamPath() {
        assertEquals("/v1/responses",
                GatewayResponsesRoutePolicy.canonicalClientUpstreamPath(null));
        assertEquals("/v1/responses/compact",
                GatewayResponsesRoutePolicy.canonicalClientUpstreamPath("/v1/responses/compact"));
        assertEquals("/v1/responses/compact",
                GatewayResponsesRoutePolicy.canonicalClientUpstreamPath("/responses/compact"));
        assertEquals("/backend-api/codex/responses/compact",
                GatewayResponsesRoutePolicy.canonicalClientUpstreamPath("/backend-api/codex/responses/compact"));
    }

    @Test
    @DisplayName("OpenAI public Responses 上游路径从三类客户端入口派生")
    void derivesOpenAiPublicResponsesPath() {
        assertEquals("/v1/responses",
                GatewayResponsesRoutePolicy.openAiPublicResponsesPath(null));
        assertEquals("/v1/responses/compact",
                GatewayResponsesRoutePolicy.openAiPublicResponsesPath("/v1/responses/compact"));
        assertEquals("/v1/responses/compact",
                GatewayResponsesRoutePolicy.openAiPublicResponsesPath("/responses/compact"));
        assertEquals("/v1/responses/compact",
                GatewayResponsesRoutePolicy.openAiPublicResponsesPath("/backend-api/codex/responses/compact"));
    }

    @Test
    @DisplayName("Codex Responses 上游路径从三类客户端入口派生")
    void derivesCodexResponsesPath() {
        assertEquals("/backend-api/codex/responses",
                GatewayResponsesRoutePolicy.codexResponsesPath(null));
        assertEquals("/backend-api/codex/responses/compact",
                GatewayResponsesRoutePolicy.codexResponsesPath("/v1/responses/compact"));
        assertEquals("/backend-api/codex/responses/compact",
                GatewayResponsesRoutePolicy.codexResponsesPath("/responses/compact"));
        assertEquals("/backend-api/codex/responses/compact",
                GatewayResponsesRoutePolicy.codexResponsesPath("/backend-api/codex/responses/compact"));
    }

    @Test
    @DisplayName("Responses compact path detection is centralized")
    void compactPathDetectionIsCentralized() {
        assertTrue(GatewayResponsesRoutePolicy.isCompactPath("/v1/responses/compact"));
        assertTrue(GatewayResponsesRoutePolicy.isCompactPath("https://api.openai.com/v1/responses/compact?x=1"));
        assertTrue(GatewayResponsesRoutePolicy.isCompactPath("/backend-api/codex/responses/compact/foo"));
        assertFalse(GatewayResponsesRoutePolicy.isCompactPath("/v1/responses"));
    }
}
