package com.landgate.trigger.gateway.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("FingerprintService 测试")
class FingerprintServiceTest {

    @Test
    @DisplayName("大小写不敏感读取 User-Agent")
    void readsUserAgentCaseInsensitively() {
        FingerprintService service = new FingerprintService();

        var fingerprint = service.getOrCreateFingerprint(1L,
                Map.of("user-agent", "claude-cli/2.1.80 (external, cli)"));

        assertEquals("claude-cli/2.1.80 (external, cli)", fingerprint.getUserAgent());
    }
}
