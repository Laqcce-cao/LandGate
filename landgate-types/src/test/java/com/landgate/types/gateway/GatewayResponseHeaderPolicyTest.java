package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GatewayResponseHeaderPolicy 测试")
class GatewayResponseHeaderPolicyTest {

    @Test
    @DisplayName("默认响应头白名单与 hop-by-hop 规则集中维护")
    void defaultHeaderPolicyIsCentralized() {
        assertTrue(GatewayResponseHeaderPolicy.shouldCopy("x-request-id", false));
        assertTrue(GatewayResponseHeaderPolicy.shouldCopy("Content-Encoding", false));
        assertTrue(GatewayResponseHeaderPolicy.shouldCopy("www-authenticate", false));
        assertFalse(GatewayResponseHeaderPolicy.shouldCopy("content-type", false));
        assertFalse(GatewayResponseHeaderPolicy.shouldCopy("content-length", false));
        assertFalse(GatewayResponseHeaderPolicy.shouldCopy("transfer-encoding", false));
        assertFalse(GatewayResponseHeaderPolicy.shouldCopy("connection", false));
        assertFalse(GatewayResponseHeaderPolicy.shouldCopy("set-cookie", false));
    }

    @Test
    @DisplayName("Codex 响应头只在 passthrough 路径透传")
    void codexHeadersOnlyPassOnPassthroughRoutes() {
        assertTrue(GatewayResponseHeaderPolicy.shouldCopy("x-codex-turn-state", true));
        assertFalse(GatewayResponseHeaderPolicy.shouldCopy("x-codex-turn-state", false));
    }
}
