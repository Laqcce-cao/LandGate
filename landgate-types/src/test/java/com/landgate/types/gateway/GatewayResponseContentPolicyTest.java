package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GatewayResponseContentPolicy 测试")
class GatewayResponseContentPolicyTest {

    @Test
    @DisplayName("event-stream Content-Type 判断集中维护")
    void detectsEventStreamContentType() {
        assertTrue(GatewayResponseContentPolicy.isEventStream("text/event-stream"));
        assertTrue(GatewayResponseContentPolicy.isEventStream("text/event-stream;charset=UTF-8"));
        assertTrue(GatewayResponseContentPolicy.isEventStream("TEXT/EVENT-STREAM; charset=utf-8"));
        assertFalse(GatewayResponseContentPolicy.isEventStream("application/json"));
        assertFalse(GatewayResponseContentPolicy.isEventStream(null));
    }
}
