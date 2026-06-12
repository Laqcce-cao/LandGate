package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.trigger.gateway.transformer.AnthropicTransformer;
import com.landgate.trigger.gateway.transformer.UpstreamRequestContext;
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
}
