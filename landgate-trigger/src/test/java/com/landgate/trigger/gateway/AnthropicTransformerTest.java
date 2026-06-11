package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.oauth.BillingHeaderService;
import com.landgate.trigger.gateway.oauth.FingerprintService;
import com.landgate.trigger.gateway.oauth.OAuthMimicryService;
import com.landgate.trigger.gateway.oauth.UserIdRewriter;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AnthropicTransformer 单元测试 —— 验证 HTTP 请求构造只消费已解析路由，不重新决策上游端点。
 */
@DisplayName("AnthropicTransformer 测试")
class AnthropicTransformerTest {

    @Test
    @DisplayName("构建请求时优先使用 UpstreamRoute 的 targetUrl")
    void buildRequestUsesRouteTargetUrlBeforeAccountExtraBaseUrl() {
        AnthropicTransformer transformer = new AnthropicTransformer(null, null, null, null);
        AccountEntity account = AccountEntity.builder()
                .id(1L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.API_KEY)
                .extra("{\"base_url\":\"https://extra.example.com\"}")
                .build();
        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                "{\"model\":\"claude-test\"}",
                account,
                "token-1",
                new UpstreamRoute(
                        Platform.ANTHROPIC,
                        "messages",
                        "messages",
                        EndpointKind.ANTHROPIC_MESSAGES,
                        "https://route.example.com/v1/messages",
                        false,
                        false,
                        false,
                        "messages",
                        "test_route"),
                null,
                null,
                null,
                false,
                false,
                Map.of()));

        assertEquals("https://route.example.com/v1/messages", request.uri().toString());
    }

    @Test
    @DisplayName("伪装模式只发送完整 anthropic-beta 头")
    void mimicryModeOnlySendsFullAnthropicBetaHeader() {
        AnthropicTransformer transformer = new AnthropicTransformer(
                new FingerprintService(),
                new UserIdRewriter(),
                new BillingHeaderService(),
                new OAuthMimicryService());
        AccountEntity account = AccountEntity.builder()
                .id(1L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.OAUTH)
                .extra("{\"account_uuid\":\"acc-1\"}")
                .build();

        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                "{\"model\":\"claude-sonnet-4-5\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
                account,
                "token-1",
                new UpstreamRoute(
                        Platform.ANTHROPIC,
                        "messages",
                        "messages",
                        EndpointKind.ANTHROPIC_MESSAGES,
                        "https://route.example.com/v1/messages",
                        false,
                        false,
                        false,
                        "messages",
                        "test_route"),
                null,
                null,
                "claude-sonnet-4-5",
                false,
                true,
                Map.of("User-Agent", "claude-cli/2.1.92 (external, cli)")));

        var betaValues = request.headers().allValues("anthropic-beta");
        assertEquals(1, betaValues.size());
        assertEquals("claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14,"
                + "prompt-caching-scope-2026-01-05,effort-2025-11-24,"
                + "context-management-2025-06-27,extended-cache-ttl-2025-04-11",
                betaValues.get(0));
    }
}
