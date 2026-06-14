package com.landgate.trigger.gateway.route;

import com.landgate.trigger.gateway.converter.ProtocolFormatResolver;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.GatewayProtocolFormat;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * OpenAI API Key 路由策略 —— 根据客户端格式和 Responses 能力选择 Responses 或 Chat Completions 端点。
 */
@Component
@Order(20)
public class OpenAiApiKeyRouteStrategy implements UpstreamRouteStrategy {

    @Override
    public boolean supports(UpstreamRouteRequest request) {
        return request != null
                && request.account() != null
                && request.account().getPlatform() == Platform.OPENAI
                && request.account().getType() == AccountType.API_KEY;
    }

    @Override
    public UpstreamRoute resolve(UpstreamRouteRequest request) {
        String upstreamFormat = ProtocolFormatResolver.requireSingleAccountUpstreamFormat(
                request.account(), java.util.Set.of(
                        GatewayProtocolFormat.RESPONSES.id(),
                        GatewayProtocolFormat.CHAT_COMPLETIONS.id()));
        boolean useResponses = GatewayProtocolFormat.RESPONSES.is(upstreamFormat);
        UpstreamEndpointProfile endpointProfile = useResponses
                ? UpstreamEndpointProfile.OPENAI_RESPONSES
                : UpstreamEndpointProfile.OPENAI_CHAT_COMPLETIONS;
        boolean forceStreaming = useResponses
                && (GatewayProtocolFormat.CHAT_COMPLETIONS.is(request.requestFormat())
                || GatewayProtocolFormat.MESSAGES.is(request.requestFormat()));

        return new UpstreamRoute(
                Platform.OPENAI,
                request.requestFormat(),
                upstreamFormat,
                endpointProfile.endpointKind(),
                endpointProfile.targetUrl(request),
                forceStreaming,
                false,
                upstreamFormat,
                useResponses ? "openai_api_key_responses" : "openai_api_key_chat_completions"
        );
    }
}
