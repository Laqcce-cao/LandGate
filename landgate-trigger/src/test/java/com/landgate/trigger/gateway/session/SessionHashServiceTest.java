package com.landgate.trigger.gateway.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.landgate.types.gateway.GatewayProtocolFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@DisplayName("SessionHashService 测试")
class SessionHashServiceTest {

    @Test
    @DisplayName("prompt_cache_key 优先作为粘滞会话信号")
    void promptCacheKeyOverridesIpAndUserAgent() {
        SessionHashService service = newService();
        String body = "{\"prompt_cache_key\":\"tenant:thread\"}";

        String h1 = service.generateHash(request("1.1.1.1", "ua/1.0.0"), 10L, body);
        String h2 = service.generateHash(request("2.2.2.2", "ua/2.0.0"), 10L, body);
        String h3 = service.generateHash(request("2.2.2.2", "ua/2.0.0"), 11L, body);

        assertEquals(h1, h2);
        assertNotEquals(h1, h3);
    }

    @Test
    @DisplayName("没有 prompt_cache_key 时回退到 IP/UA/API Key")
    void fallsBackToRequestContextWithoutPromptCacheKey() {
        SessionHashService service = newService();

        String h1 = service.generateHash(request("1.1.1.1", "ua/1.0.0"), 10L, "{}");
        String h2 = service.generateHash(request("2.2.2.2", "ua/1.0.0"), 10L, "{}");

        assertNotEquals(h1, h2);
    }

    @Test
    @DisplayName("OpenAI Chat/Responses 使用 content-derived session seed 作为粘滞信号")
    void openAiContentSeedOverridesIpAndUserAgentForOpenAiFormats() {
        SessionHashService service = newService();
        String turn1 = """
                {
                  "model":"gpt-5.4",
                  "messages":[
                    {"role":"developer","content":"Project rules"},
                    {"role":"user","content":"Hello"}
                  ]
                }
                """;
        String turn2 = """
                {
                  "model":"gpt-5.4",
                  "messages":[
                    {"role":"developer","content":"Project rules"},
                    {"role":"user","content":"Hello"},
                    {"role":"assistant","content":"Hi"},
                    {"role":"user","content":"Next"}
                  ]
                }
                """;

        String h1 = service.generateHash(request("1.1.1.1", "ua/1.0.0"), 10L,
                turn1, GatewayProtocolFormat.CHAT_COMPLETIONS.id());
        String h2 = service.generateHash(request("2.2.2.2", "ua/2.0.0"), 10L,
                turn2, GatewayProtocolFormat.CHAT_COMPLETIONS.id());
        String h3 = service.generateHash(request("2.2.2.2", "ua/2.0.0"), 11L,
                turn2, GatewayProtocolFormat.CHAT_COMPLETIONS.id());

        assertEquals(h1, h2);
        assertNotEquals(h1, h3);
    }

    @Test
    @DisplayName("Messages 格式不启用 OpenAI content-derived fallback")
    void messagesFormatDoesNotUseOpenAiContentSeedFallback() {
        SessionHashService service = newService();
        String body = """
                {
                  "model":"claude-sonnet-4-5",
                  "messages":[{"role":"user","content":"Hello"}]
                }
                """;

        String h1 = service.generateHash(request("1.1.1.1", "ua/1.0.0"), 10L,
                body, GatewayProtocolFormat.MESSAGES.id());
        String h2 = service.generateHash(request("2.2.2.2", "ua/1.0.0"), 10L,
                body, GatewayProtocolFormat.MESSAGES.id());

        assertNotEquals(h1, h2);
    }

    @Test
    @DisplayName("Anthropic cache_control 锚点优先作为粘滞会话信号")
    void anthropicCacheControlAnchorsOverrideIpAndUserAgent() {
        SessionHashService service = newService();
        String body = """
                {
                    "system": [{"type":"text","text":"project instructions","cache_control":{"type":"ephemeral"}}],
                    "messages": [
                        {"role":"user","content":[{"type":"text","text":"repo anchor","cache_control":{"type":"ephemeral"}}]}
                    ]
                }""";

        String h1 = service.generateHash(request("1.1.1.1", "ua/1.0.0"), 10L, body);
        String h2 = service.generateHash(request("2.2.2.2", "ua/2.0.0"), 10L, body);
        String h3 = service.generateHash(request("2.2.2.2", "ua/2.0.0"), 11L, body);

        assertEquals(h1, h2);
        assertNotEquals(h1, h3);
    }

    private static SessionHashService newService() {
        return new SessionHashService((org.redisson.api.RMapCache<String, Long>) null);
    }

    private static MockHttpServletRequest request(String remoteAddr, String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        request.addHeader("User-Agent", userAgent);
        return request;
    }
}
