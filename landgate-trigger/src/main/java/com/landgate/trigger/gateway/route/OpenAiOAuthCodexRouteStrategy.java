package com.landgate.trigger.gateway.route;

import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * OpenAI OAuth Codex 路由策略 —— OAuth 账号固定走 ChatGPT 内部 Codex Responses 端点。
 */
@Component
@Order(10)
public class OpenAiOAuthCodexRouteStrategy implements UpstreamRouteStrategy {

    static final String CODEX_RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses";

    @Override
    public boolean supports(UpstreamRouteRequest request) {
        return request != null
                && request.account() != null
                && request.account().getPlatform() == Platform.OPENAI
                && request.account().getType() == AccountType.OAUTH;
    }

    @Override
    public UpstreamRoute resolve(UpstreamRouteRequest request) {
        return new UpstreamRoute(
                Platform.OPENAI,
                request.requestFormat(),
                "responses",
                EndpointKind.OPENAI_CODEX_RESPONSES,
                CODEX_RESPONSES_URL,
                false,
                true,
                true,
                "responses",
                "openai_oauth_codex"
        );
    }
}
