package com.landgate.trigger.gateway.client;

import com.landgate.types.enums.Platform;

import java.util.Map;

/**
 * Describes the client-facing gateway request shape and detected client traits.
 */
public record ClientProfile(
        Platform requestPlatform,
        String requestFormat,
        boolean claudeCode,
        String metadataUserId,
        Map<String, String> headers
) {
}
