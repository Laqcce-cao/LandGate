package com.landgate.trigger.gateway.route;

import com.landgate.trigger.gateway.converter.ProtocolFormatResolver;
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
    private static final String CODEX_RESPONSES_PATH = "/backend-api/codex/responses";
    private static final String RESPONSES_PATH = "/v1/responses";

    @Override
    public boolean supports(UpstreamRouteRequest request) {
        return request != null
                && request.account() != null
                && request.account().getPlatform() == Platform.OPENAI
                && request.account().getType() == AccountType.OAUTH;
    }

    @Override
    public UpstreamRoute resolve(UpstreamRouteRequest request) {
        String upstreamFormat = ProtocolFormatResolver.requireSingleAccountUpstreamFormat(
                request.account(), java.util.Set.of("responses"));
        String targetUrl = resolveCodexTargetUrl(request);
        boolean compact = UpstreamRoute.isCompactCodexResponsesEndpoint(
                EndpointKind.OPENAI_CODEX_RESPONSES, targetUrl);
        return new UpstreamRoute(
                Platform.OPENAI,
                request.requestFormat(),
                upstreamFormat,
                EndpointKind.OPENAI_CODEX_RESPONSES,
                targetUrl,
                !compact,
                true,
                upstreamFormat,
                "openai_oauth_codex"
        );
    }

    private static String resolveCodexTargetUrl(UpstreamRouteRequest request) {
        String path = request.upstreamPath();
        if (path == null || path.isBlank()) {
            return CODEX_RESPONSES_URL;
        }
        if (path.startsWith(CODEX_RESPONSES_PATH)) {
            return CODEX_RESPONSES_URL + path.substring(CODEX_RESPONSES_PATH.length());
        }
        if (path.startsWith(RESPONSES_PATH)) {
            return CODEX_RESPONSES_URL + path.substring(RESPONSES_PATH.length());
        }
        if (path.startsWith("/responses")) {
            return CODEX_RESPONSES_URL + path.substring("/responses".length());
        }
        return CODEX_RESPONSES_URL;
    }
}
