package com.landgate.trigger.gateway.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OAuthMimicryService 测试")
class OAuthMimicryServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("非 Claude Code 伪装标准化后保留 Claude Code system 缓存断点")
    void normalizeKeepsGeneratedClaudeCodeCacheControl() throws Exception {
        OAuthMimicryService service = new OAuthMimicryService();
        String body = """
                {
                  "model": "claude-sonnet-4-5",
                  "system": "You are helpful.",
                  "messages": [
                    {"role": "user", "content": [{"type": "text", "text": "Please summarize this."}]}
                  ]
                }
                """;

        String rewritten = service.rewriteSystemForNonClaudeCode(body, "claude-sonnet-4-5");
        String normalized = service.normalizeClaudeOAuthRequestBody(
                rewritten, "claude-sonnet-4-5", false, null, true);
        JsonNode system = JSON.readTree(normalized).path("system");

        assertTrue(system.isArray());
        JsonNode claudeCodePrompt = system.get(1);
        assertEquals("You are Claude Code, Anthropic's official CLI for Claude.",
                claudeCodePrompt.path("text").asText());
        assertEquals("ephemeral", claudeCodePrompt.path("cache_control").path("type").asText());
    }

    @Test
    @DisplayName("默认标准化不保留客户端自带的 Claude Code system 缓存断点")
    void normalizeStripsClientSuppliedClaudeCodeCacheControlByDefault() throws Exception {
        OAuthMimicryService service = new OAuthMimicryService();
        String body = """
                {
                  "model": "claude-sonnet-4-5",
                  "system": [
                    {
                      "type": "text",
                      "text": "You are Claude Code, Anthropic's official CLI for Claude.",
                      "cache_control": {"type": "ephemeral"}
                    }
                  ],
                  "messages": [
                    {"role": "user", "content": [{"type": "text", "text": "Hello"}]}
                  ]
                }
                """;

        String normalized = service.normalizeClaudeOAuthRequestBody(body, "claude-sonnet-4-5");
        JsonNode systemBlock = JSON.readTree(normalized).path("system").get(0);

        assertTrue(systemBlock.path("cache_control").isMissingNode());
    }

    @Test
    @DisplayName("保留开关只保留带网关 billing 前缀的 Claude Code 缓存断点")
    void preserveFlagDoesNotKeepBareClientClaudeCodeCacheControl() throws Exception {
        OAuthMimicryService service = new OAuthMimicryService();
        String body = """
                {
                  "model": "claude-sonnet-4-5",
                  "system": [
                    {
                      "type": "text",
                      "text": "You are Claude Code, Anthropic's official CLI for Claude.",
                      "cache_control": {"type": "ephemeral"}
                    }
                  ],
                  "messages": [
                    {"role": "user", "content": [{"type": "text", "text": "Hello"}]}
                  ]
                }
                """;

        String normalized = service.normalizeClaudeOAuthRequestBody(
                body, "claude-sonnet-4-5", false, null, true);
        JsonNode systemBlock = JSON.readTree(normalized).path("system").get(0);

        assertTrue(systemBlock.path("cache_control").isMissingNode());
    }
}
