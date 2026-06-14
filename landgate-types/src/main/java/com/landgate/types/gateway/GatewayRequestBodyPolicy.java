package com.landgate.types.gateway;

/**
 * Common gateway request body facts shared by supported client protocols.
 *
 * <p>This type owns only field names and neutral defaults that are common at
 * the gateway boundary. Protocol-specific fields remain in their protocol
 * policies.</p>
 */
public final class GatewayRequestBodyPolicy {

    public static final String FIELD_MODEL = "model";
    public static final String FIELD_STREAM = "stream";
    public static final String DEFAULT_MODEL = "unknown";

    private GatewayRequestBodyPolicy() {
    }
}
