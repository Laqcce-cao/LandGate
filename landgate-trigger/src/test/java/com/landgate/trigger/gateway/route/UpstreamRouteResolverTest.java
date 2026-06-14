package com.landgate.trigger.gateway.route;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.GatewayProtocolPlanner;
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

    private final GatewayProtocolPlanner protocolPlanner = new GatewayProtocolPlanner();
    private final UpstreamRouteResolver resolver = new UpstreamRouteResolver(List.of(
            new OpenAiOAuthCodexRouteStrategy(),
            new OpenAiApiKeyRouteStrategy(),
            new AnthropicRouteStrategy()
    ));

    @Test
    @DisplayName("三协议入口按账号上游协议形成显式路由矩阵")
    void coreProtocolRouteMatrixUsesAccountProtocolAsUpstreamSource() {
        List<RouteMatrixCase> cases = List.of(
                matrix("messages -> Anthropic messages", Platform.ANTHROPIC, AccountType.API_KEY, "messages",
                        Platform.ANTHROPIC, "messages", EndpointKind.ANTHROPIC_MESSAGES,
                        "messages", false, false, true),
                matrix("responses -> Anthropic messages", Platform.ANTHROPIC, AccountType.API_KEY, "messages",
                        Platform.OPENAI, "responses", EndpointKind.ANTHROPIC_MESSAGES,
                        "messages", false, false, false),
                matrix("chat -> Anthropic messages", Platform.ANTHROPIC, AccountType.API_KEY, "messages",
                        Platform.OPENAI, "chat_completions", EndpointKind.ANTHROPIC_MESSAGES,
                        "messages", false, false, false),

                matrix("messages -> OpenAI OAuth Codex responses", Platform.OPENAI, AccountType.OAUTH, "responses",
                        Platform.ANTHROPIC, "messages", EndpointKind.OPENAI_CODEX_RESPONSES,
                        "responses", true, true, false),
                matrix("responses -> OpenAI OAuth Codex responses", Platform.OPENAI, AccountType.OAUTH, "responses",
                        Platform.OPENAI, "responses", EndpointKind.OPENAI_CODEX_RESPONSES,
                        "responses", true, true, true),
                matrix("chat -> OpenAI OAuth Codex responses", Platform.OPENAI, AccountType.OAUTH, "responses",
                        Platform.OPENAI, "chat_completions", EndpointKind.OPENAI_CODEX_RESPONSES,
                        "responses", true, true, false),

                matrix("messages -> OpenAI API Key responses", Platform.OPENAI, AccountType.API_KEY, "responses",
                        Platform.ANTHROPIC, "messages", EndpointKind.OPENAI_RESPONSES,
                        "responses", true, false, false),
                matrix("responses -> OpenAI API Key responses", Platform.OPENAI, AccountType.API_KEY, "responses",
                        Platform.OPENAI, "responses", EndpointKind.OPENAI_RESPONSES,
                        "responses", false, false, true),
                matrix("chat -> OpenAI API Key responses", Platform.OPENAI, AccountType.API_KEY, "responses",
                        Platform.OPENAI, "chat_completions", EndpointKind.OPENAI_RESPONSES,
                        "responses", true, false, false),

                matrix("messages -> OpenAI API Key chat", Platform.OPENAI, AccountType.API_KEY, "chat_completions",
                        Platform.ANTHROPIC, "messages", EndpointKind.OPENAI_CHAT_COMPLETIONS,
                        "chat_completions", false, false, false),
                matrix("responses -> OpenAI API Key chat", Platform.OPENAI, AccountType.API_KEY, "chat_completions",
                        Platform.OPENAI, "responses", EndpointKind.OPENAI_CHAT_COMPLETIONS,
                        "chat_completions", false, false, false),
                matrix("chat -> OpenAI API Key chat", Platform.OPENAI, AccountType.API_KEY, "chat_completions",
                        Platform.OPENAI, "chat_completions", EndpointKind.OPENAI_CHAT_COMPLETIONS,
                        "chat_completions", false, false, true)
        );

        for (RouteMatrixCase expected : cases) {
            AccountEntity account = account(expected.accountPlatform(), expected.accountType(),
                    "{}", "[\"" + expected.accountProtocol() + "\"]");
            UpstreamRoute route = resolver.resolve(request(account, expected.requestPlatform(), expected.clientFormat()));
            var plan = protocolPlanner.plan(expected.requestPlatform(), route);

            assertEquals(expected.endpointKind(), route.endpointKind(), expected.name());
            assertEquals(expected.accountPlatform(), route.upstreamPlatform(), expected.name());
            assertEquals(expected.clientFormat(), route.clientFormat(), expected.name());
            assertEquals(expected.upstreamFormat(), route.upstreamFormat(), expected.name());
            assertEquals(expected.upstreamFormat(), route.usageFormat(), expected.name());
            assertEquals(expected.forceStreaming(), route.forceStreaming(), expected.name());
            assertEquals(expected.normalizeCodexOAuthBody(), route.normalizeCodexOAuthBody(), expected.name());
            assertEquals(expected.passthrough(), plan.passthrough(), expected.name());
            assertEquals(!expected.passthrough(), plan.translationRequired(), expected.name());
            assertFalse(route.reason().isBlank(), expected.name());
        }
    }

    @Test
    @DisplayName("OpenAI OAuth 账号路由到 Codex Responses 并强制上游流式")
    void openAiOAuthRoutesToCodexResponses() {
        AccountEntity account = account(Platform.OPENAI, AccountType.OAUTH, null, "[\"responses\"]");

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
    @DisplayName("OpenAI API Key 账号协议为 responses 时路由到 /v1/responses")
    void openAiApiKeyRoutesToResponsesByAccountProtocol() {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY,
                "{}", "[\"responses\"]");

        UpstreamRoute route = resolver.resolve(request(account, Platform.OPENAI, "responses"));

        assertEquals(EndpointKind.OPENAI_RESPONSES, route.endpointKind());
        assertEquals("responses", route.clientFormat());
        assertEquals("responses", route.upstreamFormat());
        assertEquals("responses", route.usageFormat());
        assertEquals("https://api.openai.com/v1/responses", route.targetUrl());
        assertFalse(route.forceStreaming());
        assertFalse(route.normalizeCodexOAuthBody());
    }

    @Test
    @DisplayName("OpenAI API Key 保留 Responses 子路径")
    void openAiApiKeyPreservesResponsesSubpath() {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY,
                "{\"base_url\":\"https://proxy.example.com\"}", "[\"responses\"]");

        UpstreamRoute route = resolver.resolve(UpstreamRouteRequest.builder()
                .account(account)
                .requestPlatform(Platform.OPENAI)
                .requestFormat("responses")
                .upstreamPath("/v1/responses/compact")
                .requestedModel("test-model")
                .build());

        assertEquals(EndpointKind.OPENAI_RESPONSES, route.endpointKind());
        assertEquals("https://proxy.example.com/v1/responses/compact", route.targetUrl());
        assertTrue(route.forceNonStreamingResponse());
    }

    @Test
    @DisplayName("OpenAI OAuth Codex 保留 Responses 子路径并映射到内部端点")
    void openAiOAuthPreservesResponsesSubpath() {
        AccountEntity account = account(Platform.OPENAI, AccountType.OAUTH, null, "[\"responses\"]");

        UpstreamRoute route = resolver.resolve(UpstreamRouteRequest.builder()
                .account(account)
                .requestPlatform(Platform.OPENAI)
                .requestFormat("responses")
                .upstreamPath("/v1/responses/compact")
                .requestedModel("test-model")
                .build());

        assertEquals(EndpointKind.OPENAI_CODEX_RESPONSES, route.endpointKind());
        assertEquals("https://chatgpt.com/backend-api/codex/responses/compact", route.targetUrl());
        assertFalse(route.forceStreaming());
        assertTrue(route.forceNonStreamingResponse());
    }

    @Test
    @DisplayName("OpenAI API Key 账号协议为 chat_completions 时路由到 Chat Completions")
    void openAiApiKeyRoutesToChatCompletionsByAccountProtocol() {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY,
                "{}", "[\"chat_completions\"]");

        UpstreamRoute route = resolver.resolve(request(account, Platform.OPENAI, "responses"));

        assertEquals(EndpointKind.OPENAI_CHAT_COMPLETIONS, route.endpointKind());
        assertEquals("responses", route.clientFormat());
        assertEquals("chat_completions", route.upstreamFormat());
        assertEquals("chat_completions", route.usageFormat());
        assertEquals("https://api.openai.com/v1/chat/completions", route.targetUrl());
        assertFalse(route.normalizeCodexOAuthBody());
    }

    @Test
    @DisplayName("OpenAI API Key 优先使用账户协议字段而不是客户端格式")
    void openAiApiKeyUsesAccountProtocolAsUpstreamFormat() {
        AccountEntity chatAccount = account(Platform.OPENAI, AccountType.API_KEY,
                "{}", "[\"chat_completions\"]");
        AccountEntity responsesAccount = account(Platform.OPENAI, AccountType.API_KEY,
                "{}", "[\"responses\"]");

        UpstreamRoute chat = resolver.resolve(request(chatAccount, Platform.OPENAI, "responses"));
        UpstreamRoute responses = resolver.resolve(request(responsesAccount, Platform.OPENAI, "chat_completions"));

        assertEquals(EndpointKind.OPENAI_CHAT_COMPLETIONS, chat.endpointKind());
        assertEquals("responses", chat.clientFormat());
        assertEquals("chat_completions", chat.upstreamFormat());
        assertFalse(protocolPlanner.plan(Platform.OPENAI, chat).passthrough());
        assertEquals(EndpointKind.OPENAI_RESPONSES, responses.endpointKind());
        assertEquals("chat_completions", responses.clientFormat());
        assertEquals("responses", responses.upstreamFormat());
        assertTrue(responses.forceStreaming());
        assertFalse(protocolPlanner.plan(Platform.OPENAI, responses).passthrough());
    }

    @Test
    @DisplayName("透传由客户端协议和账户上游协议相等决定，忽略账户 passthrough 字段")
    void passthroughDependsOnClientAndUpstreamFormatOnly() {
        AccountEntity legacyPassthrough = account(Platform.OPENAI, AccountType.API_KEY,
                "{\"openai_passthrough\":true}", "[\"chat_completions\"]");
        AccountEntity sameProtocol = account(Platform.OPENAI, AccountType.API_KEY,
                "{\"openai_passthrough\":false}", "[\"responses\"]");

        UpstreamRoute translated = resolver.resolve(request(legacyPassthrough, Platform.OPENAI, "responses"));
        UpstreamRoute passthrough = resolver.resolve(request(sameProtocol, Platform.OPENAI, "responses"));

        assertFalse(protocolPlanner.plan(Platform.OPENAI, translated).passthrough());
        assertTrue(protocolPlanner.plan(Platform.OPENAI, passthrough).passthrough());
    }

    @Test
    @DisplayName("OpenAI API Key 的 base_url 根据目标端点拼接路径")
    void openAiApiKeyBaseUrlUsesResolvedEndpointPath() {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY,
                "{\"base_url\":\"https://proxy.example.com\"}", "[\"chat_completions\"]");

        UpstreamRoute route = resolver.resolve(request(account, Platform.OPENAI, "responses"));

        assertEquals(EndpointKind.OPENAI_CHAT_COMPLETIONS, route.endpointKind());
        assertEquals("https://proxy.example.com/v1/chat/completions", route.targetUrl());
    }

    @Test
    @DisplayName("OpenAI API Key base_url 按 sub2api 规则避免重复 /v1")
    void openAiApiKeyBaseUrlAvoidsDuplicateV1() {
        AccountEntity chatAccount = account(Platform.OPENAI, AccountType.API_KEY,
                "{\"base_url\":\"https://proxy.example.com/v1\"}", "[\"chat_completions\"]");
        AccountEntity chatEndpointAccount = account(Platform.OPENAI, AccountType.API_KEY,
                "{\"base_url\":\"https://proxy.example.com/v1/chat/completions\"}", "[\"chat_completions\"]");
        AccountEntity responsesAccount = account(Platform.OPENAI, AccountType.API_KEY,
                "{\"base_url\":\"https://proxy.example.com/v1\"}", "[\"responses\"]");
        AccountEntity responsesEndpointAccount = account(Platform.OPENAI, AccountType.API_KEY,
                "{\"base_url\":\"https://proxy.example.com/v1/responses\"}", "[\"responses\"]");

        UpstreamRoute chat = resolver.resolve(request(chatAccount, Platform.OPENAI, "responses"));
        UpstreamRoute chatEndpoint = resolver.resolve(request(chatEndpointAccount, Platform.OPENAI, "responses"));
        UpstreamRoute responses = resolver.resolve(request(responsesAccount, Platform.OPENAI, "responses"));
        UpstreamRoute compact = resolver.resolve(UpstreamRouteRequest.builder()
                .account(responsesEndpointAccount)
                .requestPlatform(Platform.OPENAI)
                .requestFormat("responses")
                .upstreamPath("/v1/responses/compact")
                .requestedModel("test-model")
                .build());

        assertEquals("https://proxy.example.com/v1/chat/completions", chat.targetUrl());
        assertEquals("https://proxy.example.com/v1/chat/completions", chatEndpoint.targetUrl());
        assertEquals("https://proxy.example.com/v1/responses", responses.targetUrl());
        assertEquals("https://proxy.example.com/v1/responses/compact", compact.targetUrl());
    }

    @Test
    @DisplayName("Anthropic 使用 messages 上游格式并拼接 base_url")
    void anthropicUsesMessagesWithBaseUrl() {
        UpstreamRoute anthropic = resolver.resolve(request(
                account(Platform.ANTHROPIC, AccountType.API_KEY,
                        "{\"base_url\":\"https://anthropic-proxy.example.com\"}", "[\"messages\"]"),
                Platform.OPENAI, "chat_completions"));

        assertEquals(EndpointKind.ANTHROPIC_MESSAGES, anthropic.endpointKind());
        assertEquals("messages", anthropic.upstreamFormat());
        assertEquals("https://anthropic-proxy.example.com/v1/messages", anthropic.targetUrl());
    }

    @Test
    @DisplayName("账号缺少明确上游协议时拒绝路由")
    void missingAccountProtocolFailsRouting() {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY, "{}", null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(request(account, Platform.OPENAI, "responses")));

        assertTrue(error.getMessage().contains("supportedProtocols must contain exactly one"));
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
        return account(platform, type, extra, null);
    }

    private static AccountEntity account(Platform platform, AccountType type, String extra, String supportedProtocols) {
        return AccountEntity.builder()
                .id(1L)
                .platform(platform)
                .type(type)
                .extra(extra)
                .supportedProtocols(supportedProtocols)
                .build();
    }

    private static RouteMatrixCase matrix(String name,
                                          Platform accountPlatform,
                                          AccountType accountType,
                                          String accountProtocol,
                                          Platform requestPlatform,
                                          String clientFormat,
                                          EndpointKind endpointKind,
                                          String upstreamFormat,
                                          boolean forceStreaming,
                                          boolean normalizeCodexOAuthBody,
                                          boolean passthrough) {
        return new RouteMatrixCase(name, accountPlatform, accountType, accountProtocol,
                requestPlatform, clientFormat, endpointKind, upstreamFormat,
                forceStreaming, normalizeCodexOAuthBody, passthrough);
    }

    private record RouteMatrixCase(
            String name,
            Platform accountPlatform,
            AccountType accountType,
            String accountProtocol,
            Platform requestPlatform,
            String clientFormat,
            EndpointKind endpointKind,
            String upstreamFormat,
            boolean forceStreaming,
            boolean normalizeCodexOAuthBody,
            boolean passthrough
    ) {
    }
}
