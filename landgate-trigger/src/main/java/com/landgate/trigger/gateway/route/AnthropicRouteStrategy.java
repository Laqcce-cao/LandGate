package com.landgate.trigger.gateway.route;

import com.landgate.trigger.gateway.converter.ProtocolFormatResolver;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.GatewayProtocolFormat;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Anthropic 路由策略 —— 所有 Anthropic 账号统一发送到 Messages 端点。
 */
@Component
@Order(30)
public class AnthropicRouteStrategy implements UpstreamRouteStrategy {

    @Override
    public boolean supports(UpstreamRouteRequest request) {
        return request != null
                && request.account() != null
                && request.account().getPlatform() == Platform.ANTHROPIC;
    }

    @Override
    public UpstreamRoute resolve(UpstreamRouteRequest request) {
        String upstreamFormat = ProtocolFormatResolver.requireSingleAccountUpstreamFormat(
                request.account(), java.util.Set.of(GatewayProtocolFormat.MESSAGES.id()));
        UpstreamEndpointProfile endpointProfile = UpstreamEndpointProfile.ANTHROPIC_MESSAGES;
        return new UpstreamRoute(
                Platform.ANTHROPIC,
                request.requestFormat(),
                upstreamFormat,
                endpointProfile.endpointKind(),
                endpointProfile.targetUrl(request),
                false,
                false,
                upstreamFormat,
                "anthropic_messages"
        );
    }
}
