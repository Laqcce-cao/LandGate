package com.landgate.types.gateway;

/**
 * Stable LandGate private fields carried inside the Responses-based IR.
 *
 * <p>These fields are only for gateway cross-protocol translation. They must be
 * restored by compatible target protocols or stripped before a raw upstream
 * Responses request is sent.</p>
 */
public final class GatewayProtocolIrPolicy {

    public static final String FIELD_STOP_SEQUENCES = "_landgate_stop_sequences";

    private GatewayProtocolIrPolicy() {
    }
}
