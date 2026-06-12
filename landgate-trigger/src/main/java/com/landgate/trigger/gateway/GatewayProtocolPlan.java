package com.landgate.trigger.gateway;

import com.landgate.trigger.gateway.route.UpstreamRoute;

/**
 * Protocol execution plan for a selected upstream route.
 * <p>
 * The gateway first resolves an {@link UpstreamRoute}, then turns it into this
 * plan before touching the request body. This mirrors sub2api's shape: endpoint
 * routing decides the upstream protocol, and a separate compatibility step
 * decides whether to passthrough or translate.
 */
public record GatewayProtocolPlan(
        String clientFormat,
        String upstreamFormat,
        boolean passthrough,
        boolean translationRequired,
        String reason
) {

    public String prepareRequestBody(String requestId,
                                     String originalBody,
                                     ProtocolBodyTranslator translator) {
        if (!translationRequired) {
            return originalBody;
        }
        return translator.translate(originalBody, clientFormat, upstreamFormat);
    }

    @FunctionalInterface
    public interface ProtocolBodyTranslator {
        String translate(String body, String clientFormat, String upstreamFormat);
    }
}
