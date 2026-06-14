package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("GatewayProtocolIrPolicy 私有 IR 字段测试")
class GatewayProtocolIrPolicyTest {

    @Test
    @DisplayName("LandGate 私有 stop sequences 字段稳定")
    void privateStopSequencesFieldIsStable() {
        assertEquals("_landgate_stop_sequences", GatewayProtocolIrPolicy.FIELD_STOP_SEQUENCES);
    }
}
