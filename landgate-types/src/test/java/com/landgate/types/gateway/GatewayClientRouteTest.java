package com.landgate.types.gateway;

import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GatewayClientRoute 客户端路由事实测试")
class GatewayClientRouteTest {

    @Test
    @DisplayName("解析三协议核心入口和 Chat 别名")
    void resolvesCoreProtocolRoutes() {
        assertRoute("/v1/messages", GatewayClientRoute.MESSAGES, Platform.ANTHROPIC, "messages");
        assertRoute("/v1/chat/completions", GatewayClientRoute.CHAT_COMPLETIONS, Platform.OPENAI, "chat_completions");
        assertRoute("/chat/completions", GatewayClientRoute.CHAT_COMPLETIONS_ALIAS, Platform.OPENAI, "chat_completions");
        assertRoute("/v1/responses", GatewayClientRoute.V1_RESPONSES, Platform.OPENAI, "responses");
        assertRoute("/responses", GatewayClientRoute.RESPONSES_ALIAS, Platform.OPENAI, "responses");
        assertRoute("/backend-api/codex/responses", GatewayClientRoute.CODEX_RESPONSES, Platform.OPENAI, "responses");
    }

    @Test
    @DisplayName("优先匹配更长路径，避免通用别名抢占具体路径")
    void prefersLongestPrefix() {
        assertRoute("/backend-api/codex/responses/compact",
                GatewayClientRoute.CODEX_RESPONSES, Platform.OPENAI, "responses");
        assertRoute("/v1/responses/compact",
                GatewayClientRoute.V1_RESPONSES, Platform.OPENAI, "responses");
        assertRoute("/responses/compact",
                GatewayClientRoute.RESPONSES_ALIAS, Platform.OPENAI, "responses");
    }

    @Test
    @DisplayName("未知路径不解析为协议入口")
    void unknownPathDoesNotResolve() {
        assertTrue(GatewayClientRoute.resolve("/antigravity/v1/messages").isEmpty());
        assertTrue(GatewayClientRoute.resolve("/images/generations").isEmpty());
        assertTrue(GatewayClientRoute.resolve("/api/v1/auth/login").isEmpty());
        assertTrue(GatewayClientRoute.resolve(null).isEmpty());
    }

    private static void assertRoute(String path, GatewayClientRoute expected, Platform platform, String format) {
        GatewayClientRoute route = GatewayClientRoute.resolve(path).orElseThrow();
        assertEquals(expected, route);
        assertEquals(platform, route.platform());
        assertEquals(format, route.format());
    }
}
