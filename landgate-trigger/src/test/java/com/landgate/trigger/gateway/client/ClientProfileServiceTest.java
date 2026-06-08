package com.landgate.trigger.gateway.client;

import com.landgate.trigger.gateway.GatewayDispatcher;
import com.landgate.trigger.gateway.oauth.ClaudeCodeDetector;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ClientProfileService 测试")
class ClientProfileServiceTest {

    private final ClientProfileService service = new ClientProfileService(new ClaudeCodeDetector());

    @Test
    @DisplayName("非 Anthropic 请求只返回平台格式和 headers，不做 Claude Code 检测")
    void nonAnthropicRequestDoesNotDetectClaudeCode() {
        MockHttpServletRequest request = request(Platform.OPENAI, "responses");
        request.addHeader("User-Agent", "claude-cli/1.2.3");

        ClientProfile profile = service.detect("{\"model\":\"gpt-5.5\"}", request);

        assertEquals(Platform.OPENAI, profile.requestPlatform());
        assertEquals("responses", profile.requestFormat());
        assertFalse(profile.claudeCode());
        assertEquals("claude-cli/1.2.3", profile.headers().get("User-Agent"));
    }

    @Test
    @DisplayName("Anthropic 非 messages 端点通过 User-Agent 识别 Claude Code")
    void anthropicNonMessagesDetectsClaudeCodeByUserAgent() {
        MockHttpServletRequest request = request(Platform.ANTHROPIC, "oauth");
        request.addHeader("User-Agent", "claude-cli/1.2.3");

        ClientProfile profile = service.detect("{}", request);

        assertTrue(profile.claudeCode());
    }

    @Test
    @DisplayName("Anthropic messages 端点提取 metadata.user_id 并保留 headers")
    void anthropicMessagesExtractsMetadataUserIdAndHeaders() {
        MockHttpServletRequest request = request(Platform.ANTHROPIC, "messages");
        request.addHeader("User-Agent", "curl/8.0");
        request.addHeader("X-App", "claude-code");
        String body = """
                {
                  "model": "claude-3-5-sonnet",
                  "metadata": {"user_id": "device_id=abc,session_id=def"}
                }
                """;

        ClientProfile profile = service.detect(body, request);

        assertEquals("device_id=abc,session_id=def", profile.metadataUserId());
        assertEquals("claude-code", profile.headers().get("X-App"));
        assertFalse(profile.claudeCode());
    }

    private static MockHttpServletRequest request(Platform platform, String format) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GatewayDispatcher.ATTR_REQUEST_PLATFORM, platform);
        request.setAttribute(GatewayDispatcher.ATTR_REQUEST_FORMAT, format);
        return request;
    }
}
