package com.landgate.trigger.gateway.request;

/**
 * Parsed request properties needed before account selection.
 */
public record GatewayRequestInfo(
        String model,
        String upstreamPath,
        boolean clientStream
) {
}
