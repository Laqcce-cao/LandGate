package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GatewayPathPolicy 网关路径策略测试")
class GatewayPathPolicyTest {

    @Test
    @DisplayName("识别所有需要 API Key 过滤器覆盖的网关路径")
    void identifiesGatewayPaths() {
        assertTrue(GatewayPathPolicy.isGatewayPath("/v1/messages"));
        assertTrue(GatewayPathPolicy.isGatewayPath("/v1/chat/completions"));
        assertTrue(GatewayPathPolicy.isGatewayPath("/chat/completions"));
        assertTrue(GatewayPathPolicy.isGatewayPath("/v1/responses"));
        assertTrue(GatewayPathPolicy.isGatewayPath("/responses"));
        assertTrue(GatewayPathPolicy.isGatewayPath("/responses/compact"));
        assertTrue(GatewayPathPolicy.isGatewayPath("/backend-api/codex/responses"));
        assertTrue(GatewayPathPolicy.isGatewayPath("/v1beta/models/gemini:generateContent"));
        assertTrue(GatewayPathPolicy.isGatewayPath("/images/generations"));
        assertTrue(GatewayPathPolicy.isGatewayPath("/antigravity/v1/messages"));
    }

    @Test
    @DisplayName("非网关路径不进入 API Key 过滤器")
    void ignoresNonGatewayPaths() {
        assertFalse(GatewayPathPolicy.isGatewayPath("/api/v1/auth/login"));
        assertFalse(GatewayPathPolicy.isGatewayPath("/api/v1/user/profile"));
        assertFalse(GatewayPathPolicy.isGatewayPath("/"));
        assertFalse(GatewayPathPolicy.isGatewayPath(null));
    }

    @Test
    @DisplayName("Security matcher 显式包含 /responses 精确路径和 Chat 别名")
    void securityMatchersCoverExactAliases() {
        List<String> matchers = List.of(GatewayPathPolicy.securityMatchers());

        assertTrue(matchers.contains("/chat/completions"));
        assertTrue(matchers.contains("/responses"));
        assertTrue(matchers.contains("/responses/**"));
        assertTrue(matchers.contains("/backend-api/codex/**"));
    }
}
