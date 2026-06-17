package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("AnthropicEndpointPolicy 测试")
class AnthropicEndpointPolicyTest {

    @Test
    @DisplayName("Anthropic endpoint 基础事实集中维护")
    void endpointFactsAreCentralized() {
        assertEquals("https://api.anthropic.com", AnthropicEndpointPolicy.API_BASE_URL);
        assertEquals("/v1/messages", AnthropicEndpointPolicy.MESSAGES_PATH);
    }
}
