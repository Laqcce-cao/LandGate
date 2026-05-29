package com.landgate.trigger.gateway.route;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.UpstreamCapabilityService;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UpstreamRouteResolver 单元测试 —— 验证账号平台、账号类型与客户端格式到上游端点的路由决策。
 */
@DisplayName("UpstreamRouteResolver 上游策略路由测试")
class UpstreamRouteResolverTest {

    private final UpstreamCapabilityService capabilityService = new UpstreamCapabilityService();
    private final UpstreamRouteResolver resolver = new UpstreamRouteResolver(List.of(
            new OpenAiOAuthCodexRouteStrategy(),
            new OpenAiApiKeyRouteStrategy(capabilityService),
            new AnthropicRouteStrategy(),
            new AntigravityRouteStrategy(),
            new GeminiRouteStrategy()
    ));

    @Test
    @DisplayName("OpenAI OAuth 账号路由到 Codex Responses 并强制流式")
    void openAiOAuthRoutesToCodexResponses() {
        AccountEntity account = account(Platform.OPENAI, AccountType.OAUTH, null);

        UpstreamRoute route = resolver.resolve(request(account, Platform.ANTHROPIC, "messages"));

        assertEquals(EndpointKind.OPENAI_CODEX_RESPONSES, route.endpointKind());
        assertEquals(Platform.OPENAI, route.upstreamPlatform());
        assertEquals("messages", route.clientFormat());
        assertEquals("responses", route.upstreamFormat());
        assertEquals("responses", route.usageFormat());
        assertEquals("https://chatgpt.com/backend-api/codex/responses", route.targetUrl());
        assertTrue(route.forceStreaming());
        assertTrue(route.normalizeCodexOAuthBody());
    }

    @Test
    @DisplayName("OpenAI API Key 且 Responses 可用时路由到 /v1/responses")
    void openAiApiKeyRoutesToResponsesWhenSupported() {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY,
                "{\"openai_responses_supported\":true}");

        UpstreamRoute route = resolver.resolve(request(account, Platform.OPENAI, "responses"));

        assertEquals(EndpointKind.OPENAI_RESPONSES, route.endpointKind());
        assertEquals("responses", route.clientFormat());
        assertEquals("responses", route.upstreamFormat());
        assertEquals("responses", route.usageFormat());
        assertEquals("https://api.openai.com/v1/responses", route.targetUrl());
        assertTrue(route.forceStreaming());
        assertFalse(route.normalizeCodexOAuthBody());
    }

    @Test
    @DisplayName("OpenAI API Key 且 Responses 不可用时路由到 Chat Completions")
    void openAiApiKeyFallsBackToChatCompletionsWhenResponsesUnsupported() {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY,
                "{\"openai_responses_supported\":false}");

        UpstreamRoute route = resolver.resolve(request(account, Platform.OPENAI, "responses"));

        assertEquals(EndpointKind.OPENAI_CHAT_COMPLETIONS, route.endpointKind());
        assertEquals("responses", route.clientFormat());
        assertEquals("chat_completions", route.upstreamFormat());
        assertEquals("chat_completions", route.usageFormat());
        assertEquals("https://api.openai.com/v1/chat/completions", route.targetUrl());
        assertFalse(route.normalizeCodexOAuthBody());
    }

    @Test
    @DisplayName("OpenAI API Key 的 base_url 根据目标端点拼接路径")
    void openAiApiKeyBaseUrlUsesResolvedEndpointPath() {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY,
                "{\"base_url\":\"https://proxy.example.com\",\"openai_responses_supported\":false}");

        UpstreamRoute route = resolver.resolve(request(account, Platform.OPENAI, "responses"));

        assertEquals(EndpointKind.OPENAI_CHAT_COMPLETIONS, route.endpointKind());
        assertEquals("https://proxy.example.com/v1/chat/completions", route.targetUrl());
    }

    @Test
    @DisplayName("Anthropic 与 Antigravity 都使用 messages 格式但端点类型不同")
    void anthropicAndAntigravityUseMessagesWithDifferentEndpointKinds() {
        UpstreamRoute anthropic = resolver.resolve(request(
                account(Platform.ANTHROPIC, AccountType.API_KEY,
                        "{\"base_url\":\"https://anthropic-proxy.example.com\"}"),
                Platform.OPENAI, "chat_completions"));
        UpstreamRoute antigravity = resolver.resolve(request(
                account(Platform.ANTIGRAVITY, AccountType.API_KEY, null), Platform.ANTHROPIC, "messages"));

        assertEquals(EndpointKind.ANTHROPIC_MESSAGES, anthropic.endpointKind());
        assertEquals("messages", anthropic.upstreamFormat());
        assertEquals("https://anthropic-proxy.example.com/v1/messages", anthropic.targetUrl());
        assertEquals(EndpointKind.ANTIGRAVITY_MESSAGES, antigravity.endpointKind());
        assertEquals("messages", antigravity.upstreamFormat());
    }

    @Test
    @DisplayName("Gemini 账号路由到 Gemini generateContent 路径")
    void geminiRoutesToGenerateContent() {
        AccountEntity account = account(Platform.GEMINI, AccountType.API_KEY,
                "{\"base_url\":\"https://gemini-proxy.example.com\"}");

        UpstreamRoute route = resolver.resolve(UpstreamRouteRequest.builder()
                .account(account)
                .requestPlatform(Platform.GEMINI)
                .requestFormat("gemini")
                .upstreamPath("/v1beta/models/gemini-pro:generateContent")
                .requestedModel("gemini-pro")
                .build());

        assertEquals(EndpointKind.GEMINI_GENERATE_CONTENT, route.endpointKind());
        assertEquals("gemini", route.clientFormat());
        assertEquals("gemini", route.upstreamFormat());
        assertEquals("gemini", route.usageFormat());
        assertEquals("https://gemini-proxy.example.com/v1beta/models/gemini-pro:generateContent", route.targetUrl());
        assertFalse(route.forceStreaming());
    }

    private static UpstreamRouteRequest request(AccountEntity account, Platform requestPlatform, String requestFormat) {
        return UpstreamRouteRequest.builder()
                .account(account)
                .requestPlatform(requestPlatform)
                .requestFormat(requestFormat)
                .requestedModel("test-model")
                .build();
    }

    private static AccountEntity account(Platform platform, AccountType type, String extra) {
        return AccountEntity.builder()
                .id(1L)
                .platform(platform)
                .type(type)
                .extra(extra)
                .build();
    }
}
