package com.landgate.trigger.gateway.route;

import com.landgate.types.enums.Platform;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Antigravity 路由策略 —— 使用 Anthropic Messages 兼容协议，但端点类型与 Anthropic 官方隔离。
 */
@Component
@Order(40)
public class AntigravityRouteStrategy extends AnthropicRouteStrategy {

    @Override
    public boolean supports(UpstreamRouteRequest request) {
        return request != null
                && request.account() != null
                && request.account().getPlatform() == Platform.ANTIGRAVITY;
    }

    @Override
    public UpstreamRoute resolve(UpstreamRouteRequest request) {
        UpstreamRoute route = super.resolve(request);
        return new UpstreamRoute(
                Platform.ANTIGRAVITY,
                route.clientFormat(),
                route.upstreamFormat(),
                EndpointKind.ANTIGRAVITY_MESSAGES,
                route.targetUrl(),
                route.passthrough(),
                route.forceStreaming(),
                route.normalizeCodexOAuthBody(),
                route.usageFormat(),
                "antigravity_messages"
        );
    }
}
