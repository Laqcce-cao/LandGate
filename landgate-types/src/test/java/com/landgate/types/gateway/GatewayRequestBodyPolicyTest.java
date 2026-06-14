package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("GatewayRequestBodyPolicy 测试")
class GatewayRequestBodyPolicyTest {

    @Test
    @DisplayName("通用请求字段和默认模型稳定")
    void commonRequestFieldsAreStable() {
        assertEquals("model", GatewayRequestBodyPolicy.FIELD_MODEL);
        assertEquals("stream", GatewayRequestBodyPolicy.FIELD_STREAM);
        assertEquals("unknown", GatewayRequestBodyPolicy.DEFAULT_MODEL);
    }
}
