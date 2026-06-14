package com.landgate.trigger.gateway.route;

import com.landgate.trigger.gateway.converter.ProtocolFormatResolver;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.GatewayProtocolFormat;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * OpenAI OAuth Codex 路由策略 —— OAuth 账号固定走 ChatGPT 内部 Codex Responses 端点。
 */
@Component
@Order(10)
public class OpenAiOAuthCodexRouteStrategy implements UpstreamRouteStrategy {

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
                request.account(), java.util.Set.of(GatewayProtocolFormat.RESPONSES.id()));
        UpstreamEndpointProfile endpointProfile = UpstreamEndpointProfile.OPENAI_CODEX_RESPONSES;
        String targetUrl = endpointProfile.targetUrl(request);
        boolean compact = UpstreamRoute.isCompactCodexResponsesEndpoint(
                endpointProfile.endpointKind(), targetUrl);
        return new UpstreamRoute(
                Platform.OPENAI,
                request.requestFormat(),
                upstreamFormat,
                endpointProfile.endpointKind(),
                targetUrl,
                !compact,
                true,
                upstreamFormat,
                "openai_oauth_codex"
        );
    }
}
