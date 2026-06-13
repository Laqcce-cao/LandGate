package com.landgate.trigger.gateway;

import com.landgate.trigger.gateway.converter.ProtocolFormatResolver;
import com.landgate.trigger.gateway.converter.ProtocolTranslationService;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.Platform;
import org.springframework.stereotype.Component;

/**
 * Builds the protocol execution plan after an upstream route is selected.
 * <p>
 * This is intentionally a small policy object: route strategies decide where
 * to send the request, while this planner decides whether the body is raw
 * passthrough or Hub-and-Spoke protocol translation.
 */
@Component
public class GatewayProtocolPlanner {

    public GatewayProtocolPlan plan(Platform requestPlatform, UpstreamRoute route) {
        if (route == null) {
            String fallback = ProtocolTranslationService.platformToFormatId(requestPlatform);
            return new GatewayProtocolPlan(fallback, fallback, false, false, "missing_route_same_format");
        }

        String clientFormat = route.clientFormat();
        if (clientFormat == null || clientFormat.isBlank()) {
            clientFormat = ProtocolTranslationService.platformToFormatId(requestPlatform);
        }
        clientFormat = ProtocolFormatResolver.normalizeFormat(clientFormat);

        String upstreamFormat = ProtocolFormatResolver.normalizeFormat(route.upstreamFormat());
        boolean passthrough = ProtocolFormatResolver.isSameFormat(clientFormat, upstreamFormat);
        boolean translationRequired = !passthrough
                && clientFormat != null && upstreamFormat != null
                && !clientFormat.equals(upstreamFormat);
        String reason = passthrough
                ? "passthrough"
                : translationRequired ? "translate" : "same_format";

        return new GatewayProtocolPlan(
                clientFormat,
                upstreamFormat,
                passthrough,
                translationRequired,
                reason + ":" + route.reason()
        );
    }
}
