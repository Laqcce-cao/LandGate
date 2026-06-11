package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GeminiTransformer 单元测试 —— 验证 HTTP 请求构造使用已解析路由 URL 并追加 API Key。
 */
@DisplayName("GeminiTransformer 测试")
class GeminiTransformerTest {

    @Test
    @DisplayName("构建请求时使用 UpstreamRoute targetUrl 并追加 key 查询参数")
    void buildRequestUsesRouteTargetUrlAndAppendsAccessToken() {
        GeminiTransformer transformer = new GeminiTransformer();
        AccountEntity account = AccountEntity.builder()
                .id(1L)
                .platform(Platform.GEMINI)
                .type(AccountType.API_KEY)
                .extra("{\"base_url\":\"https://extra.example.com\"}")
                .build();
        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                "{}",
                account,
                "gemini-key",
                new UpstreamRoute(
                        Platform.GEMINI,
                        "gemini",
                        "gemini",
                        EndpointKind.GEMINI_GENERATE_CONTENT,
                        "https://route.example.com/v1beta/models/gemini-pro:generateContent",
                        false,
                        false,
                        false,
                        "gemini",
                        "test_route"),
                null,
                "/v1beta/models/ignored:generateContent",
                null,
                false,
                false,
                Map.of()));

        assertEquals("https://route.example.com/v1beta/models/gemini-pro:generateContent?key=gemini-key",
                request.uri().toString());
    }
}
