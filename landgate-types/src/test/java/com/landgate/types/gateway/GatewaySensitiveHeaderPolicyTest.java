package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GatewaySensitiveHeaderPolicy 测试")
class GatewaySensitiveHeaderPolicyTest {

    @Test
    @DisplayName("入站敏感头集合集中维护")
    void sensitiveRequestHeadersAreCentralized() {
        assertTrue(GatewaySensitiveHeaderPolicy.SENSITIVE_REQUEST_HEADERS.contains("authorization"));
        assertTrue(GatewaySensitiveHeaderPolicy.SENSITIVE_REQUEST_HEADERS.contains("x-api-key"));
        assertTrue(GatewaySensitiveHeaderPolicy.SENSITIVE_REQUEST_HEADERS.contains("x-goog-api-key"));
        assertTrue(GatewaySensitiveHeaderPolicy.SENSITIVE_REQUEST_HEADERS.contains("cookie"));
        assertTrue(GatewaySensitiveHeaderPolicy.SENSITIVE_REQUEST_HEADERS.contains("proxy-authorization"));
    }
}
