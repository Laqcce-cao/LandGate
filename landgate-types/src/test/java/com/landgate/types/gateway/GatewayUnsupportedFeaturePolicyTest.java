package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GatewayUnsupportedFeaturePolicy 测试")
class GatewayUnsupportedFeaturePolicyTest {

    @Test
    @DisplayName("当前未实现和 unsupported 响应事实集中维护")
    void unsupportedFeatureFactsAreCentralized() {
        assertEquals(404, GatewayUnsupportedFeaturePolicy.STATUS_NOT_FOUND);
        assertEquals("error", GatewayUnsupportedFeaturePolicy.ANTHROPIC_TYPE_ERROR);
        assertEquals("not_implemented", GatewayUnsupportedFeaturePolicy.ERROR_TYPE_NOT_IMPLEMENTED);
        assertEquals("unsupported_error", GatewayUnsupportedFeaturePolicy.ERROR_TYPE_UNSUPPORTED);
        assertEquals("UNSUPPORTED", GatewayUnsupportedFeaturePolicy.GOOGLE_STATUS_UNSUPPORTED);
        assertEquals("count_tokens is not yet implemented",
                GatewayUnsupportedFeaturePolicy.COUNT_TOKENS_NOT_IMPLEMENTED_MESSAGE);
        assertEquals("Gemini gateway is not supported in this build",
                GatewayUnsupportedFeaturePolicy.GEMINI_UNSUPPORTED_MESSAGE);
        assertEquals("Antigravity gateway is not supported in this build",
                GatewayUnsupportedFeaturePolicy.ANTIGRAVITY_UNSUPPORTED_MESSAGE);
    }

    @Test
    @DisplayName("unsupported 响应体构造保持客户端协议形状")
    void unsupportedBodiesKeepClientProtocolShape() {
        assertEquals("{\"type\":\"error\",\"error\":{\"type\":\"not_implemented\",\"message\":\"count_tokens is not yet implemented\"}}",
                GatewayUnsupportedFeaturePolicy.countTokensNotImplementedBody());
        assertEquals("{\"error\":{\"code\":404,\"message\":\"Gemini gateway is not supported in this build\",\"status\":\"UNSUPPORTED\"}}",
                GatewayUnsupportedFeaturePolicy.googleUnsupportedBody());
        assertEquals("{\"error\":{\"message\":\"Antigravity gateway is not supported in this build\",\"type\":\"unsupported_error\"}}",
                GatewayUnsupportedFeaturePolicy.openAiUnsupportedBody(
                        GatewayUnsupportedFeaturePolicy.ANTIGRAVITY_UNSUPPORTED_MESSAGE));
    }

    @Test
    @DisplayName("JSON 字符串转义集中维护")
    void jsonEscapingIsCentralized() {
        assertEquals("a\\\\b\\\"c", GatewayUnsupportedFeaturePolicy.escapeJson("a\\b\"c"));
    }
}
