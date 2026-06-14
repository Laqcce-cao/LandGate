package com.landgate.trigger.gateway.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import com.landgate.types.gateway.AnthropicApiProfile;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OAuthMimicryService 测试")
class OAuthMimicryServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final OAuthMimicryService service = new OAuthMimicryService();

    @Test
    @DisplayName("normalize 默认剥离 system cache_control")
    void normalizeStripsSystemCacheControlByDefault() throws Exception {
        String body = """
                {
                    "model": "claude-sonnet-4-5",
                    "system": [{"type":"text","text":"Stable","cache_control":{"type":"ephemeral"}}],
                    "messages": [{"role":"user","content":"Hello"}]
                }""";

        JsonNode normalized = JSON.readTree(service.normalizeClaudeOAuthRequestBody(body, "claude-sonnet-4-5"));

        assertFalse(normalized.get("system").get(0).has("cache_control"));
        assertEquals(AnthropicClaudeCodeProfile.normalizeModelId("claude-sonnet-4-5"),
                normalized.get(AnthropicMessagesBodyPolicy.FIELD_MODEL).asText());
    }

    @Test
    @DisplayName("normalize 可保留 rewrite 后的 system cache_control")
    void normalizeCanPreserveSystemCacheControl() throws Exception {
        String body = """
                {
                    "model": "claude-sonnet-4-5",
                    "system": [{"type":"text","text":"Stable","cache_control":{"type":"ephemeral"}}],
                    "messages": [{"role":"user","content":"Hello"}]
                }""";

        JsonNode normalized = JSON.readTree(service.normalizeClaudeOAuthRequestBody(
                body, "claude-sonnet-4-5", false, null, false));

        assertTrue(normalized.get("system").get(0).has("cache_control"));
        assertEquals("ephemeral", normalized.get("system").get(0).get("cache_control").get("type").asText());
    }

    @Test
    @DisplayName("mimic headers 使用 set 语义并合并客户端 beta")
    void mimicHeadersSetAndMergeClientBeta() {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header(AnthropicApiProfile.HEADER_ANTHROPIC_BETA, AnthropicApiProfile.BETA_OAUTH);

        service.applyClaudeCodeMimicHeaders(builder, true, "claude-sonnet-4-5",
                Map.of("Anthropic-Beta", "redact-thinking-2026-02-12,oauth-2025-04-20"));

        var headers = builder.POST(HttpRequest.BodyPublishers.noBody()).build().headers();
        assertEquals(1, headers.allValues(AnthropicApiProfile.HEADER_ANTHROPIC_BETA).size());
        assertEquals(AnthropicClaudeCodeProfile.fullMimicryBetaHeader() + ",redact-thinking-2026-02-12",
                headers.firstValue(AnthropicApiProfile.HEADER_ANTHROPIC_BETA).orElse(""));
        assertEquals(AnthropicApiProfile.STAINLESS_HELPER_METHOD_STREAM,
                headers.firstValue(AnthropicApiProfile.HEADER_STAINLESS_HELPER_METHOD).orElse(""));
    }

    @Test
    @DisplayName("真实 Claude Code headers 保留客户端 beta 并补齐 oauth")
    void oauthDefaultsPreserveClientBetaAndEnsureOauth() {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header(AnthropicApiProfile.HEADER_ANTHROPIC_BETA, AnthropicApiProfile.BETA_OAUTH);

        service.applyOAuthHeaderDefaults(builder, "claude-sonnet-4-5",
                Map.of("Anthropic-Beta", "claude-code-20250219,foo"));

        var headers = builder.POST(HttpRequest.BodyPublishers.noBody()).build().headers();
        assertEquals(1, headers.allValues(AnthropicApiProfile.HEADER_ANTHROPIC_BETA).size());
        assertEquals("claude-code-20250219,oauth-2025-04-20,foo",
                headers.firstValue(AnthropicApiProfile.HEADER_ANTHROPIC_BETA).orElse(""));
        assertEquals(AnthropicClaudeCodeProfile.DEFAULT_CLAUDE_CLI_USER_AGENT,
                headers.firstValue(AnthropicApiProfile.HEADER_USER_AGENT).orElse(""));
    }

    @Test
    @DisplayName("OAuth metadata 注入不覆盖已有 metadata.user_id")
    void buildAndInjectMetadataDoesNotOverwriteExistingUserId() throws Exception {
        AccountEntity account = AccountEntity.builder()
                .id(1L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.OAUTH)
                .extra("{\"account_uuid\":\"account-selected\"}")
                .build();
        FingerprintService.ClientFingerprint fp = new FingerprintService().getOrCreateFingerprint(1L,
                Map.of(AnthropicApiProfile.HEADER_USER_AGENT, AnthropicClaudeCodeProfile.DEFAULT_CLAUDE_CLI_USER_AGENT));
        String existingUserId = """
                {"device_id":"device-existing","account_uuid":"account-existing","session_id":"session-existing"}
                """.trim();
        String body = """
                {"model":"claude-sonnet-4-5","metadata":{"user_id":%s},"messages":[{"role":"user","content":"Hello"}]}
                """.formatted(JSON.writeValueAsString(existingUserId)).trim();

        String out = service.buildAndInjectMetadataUserID(body, account, fp);

        JsonNode root = JSON.readTree(out);
        assertEquals(existingUserId, root.get("metadata").get("user_id").asText());
    }

    @Test
    @DisplayName("post-normalize mimicry 改写工具名并同步 tool_choice 与历史 tool_use")
    void postNormalizeMimicryRewritesToolNamesAndRestoresResponses() throws Exception {
        String body = """
                {
                  "model":"claude-sonnet-4-5",
                  "tools":[
                    {"name":"sessions_read","input_schema":{}},
                    {"type":"web_search_20250305","name":"web_search_20250305","input_schema":{}}
                  ],
                  "tool_choice":{"type":"tool","name":"sessions_read"},
                  "messages":[
                    {"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"sessions_read","input":{}}]}
                  ]
                }""";

        OAuthMimicryService.ClaudeOAuthBodyMimicryResult result =
                service.applyPostNormalizeClaudeOAuthMimicry(body);
        JsonNode root = JSON.readTree(result.body());

        assertTrue(result.toolNameRewrite().hasRewrite());
        assertEquals("cc_sess_read", root.get("tools").get(0).get("name").asText());
        assertEquals("web_search_20250305", root.get("tools").get(1).get("name").asText());
        assertEquals("cc_sess_read", root.get("tool_choice").get("name").asText());
        assertEquals("cc_sess_read",
                root.get("messages").get(0).get("content").get(0).get("name").asText());
        assertEquals("ephemeral",
                root.get("tools").get(1).get("cache_control").get("type").asText());
        assertEquals(AnthropicClaudeCodeProfile.DEFAULT_CACHE_CONTROL_TTL,
                root.get("tools").get(1).get("cache_control").get("ttl").asText());

        String restored = result.toolNameRewrite().restore(
                "{\"type\":\"tool_use\",\"name\":\"cc_sess_read\"}");
        assertTrue(restored.contains("\"name\":\"sessions_read\""));
    }

    @Test
    @DisplayName("post-normalize mimicry 无需改名时仍给最后一个 tool 注入 cache_control")
    void postNormalizeMimicryAddsToolsLastCacheBreakpointWithoutRewrite() throws Exception {
        String body = """
                {
                  "model":"claude-sonnet-4-5",
                  "tools":[{"name":"read_file","input_schema":{}}],
                  "messages":[{"role":"user","content":"Use the tool"}]
                }""";

        OAuthMimicryService.ClaudeOAuthBodyMimicryResult result =
                service.applyPostNormalizeClaudeOAuthMimicry(body);
        JsonNode root = JSON.readTree(result.body());

        assertFalse(result.toolNameRewrite().hasRewrite());
        assertEquals("read_file", root.get("tools").get(0).get("name").asText());
        assertEquals("ephemeral",
                root.get("tools").get(0).get("cache_control").get("type").asText());
        assertEquals(AnthropicClaudeCodeProfile.DEFAULT_CACHE_CONTROL_TTL,
                root.get("tools").get(0).get("cache_control").get("ttl").asText());
    }

    @Test
    @DisplayName("post-normalize mimicry 默认保留客户端 messages cache_control")
    void postNormalizeMimicryKeepsClientMessageCacheControlByDefault() throws Exception {
        String body = """
                {"messages":[
                  {"role":"user","content":[{"type":"text","text":"stable","cache_control":{"type":"ephemeral","ttl":"1h"}}]},
                  {"role":"assistant","content":[{"type":"text","text":"ok"}]},
                  {"role":"user","content":[{"type":"text","text":"latest","cache_control":{"type":"ephemeral","ttl":"5m"}}]}
                ]}""";

        OAuthMimicryService.ClaudeOAuthBodyMimicryResult result =
                service.applyPostNormalizeClaudeOAuthMimicry(body);
        JsonNode root = JSON.readTree(result.body());

        assertEquals("1h",
                root.get("messages").get(0).get("content").get(0).get("cache_control").get("ttl").asText());
        assertEquals("5m",
                root.get("messages").get(2).get("content").get(0).get("cache_control").get("ttl").asText());
    }

    @Test
    @DisplayName("post-normalize mimicry 开启后按 Sub2API 改写 messages cache_control")
    void postNormalizeMimicryCanRewriteMessageCacheControlWhenEnabled() throws Exception {
        String body = """
                {"messages":[
                  {"role":"user","content":[{"type":"text","text":"stable","cache_control":{"type":"ephemeral","ttl":"1h"}}]},
                  {"role":"assistant","content":[{"type":"text","text":"ok"}]},
                  {"role":"user","content":[{"type":"text","text":"latest","cache_control":{"type":"ephemeral","ttl":"1h"}}]},
                  {"role":"assistant","content":[{"type":"text","text":"done"}]}
                ]}""";

        OAuthMimicryService.ClaudeOAuthBodyMimicryResult result =
                service.applyPostNormalizeClaudeOAuthMimicry(body, true);
        JsonNode root = JSON.readTree(result.body());

        assertEquals(AnthropicClaudeCodeProfile.DEFAULT_CACHE_CONTROL_TTL,
                root.get("messages").get(0).get("content").get(0).get("cache_control").get("ttl").asText());
        assertFalse(root.get("messages").get(2).get("content").get(0).has("cache_control"));
        assertEquals(AnthropicClaudeCodeProfile.DEFAULT_CACHE_CONTROL_TTL,
                root.get("messages").get(3).get("content").get(0).get("cache_control").get("ttl").asText());
    }

    @Test
    @DisplayName("post-normalize mimicry 开启 messages cache rewrite 时会把 string content 升级为 text block")
    void postNormalizeMimicryRewritesStringMessageContentCacheControl() throws Exception {
        String body = """
                {"messages":[
                  {"role":"user","content":"hello"}
                ]}""";

        OAuthMimicryService.ClaudeOAuthBodyMimicryResult result =
                service.applyPostNormalizeClaudeOAuthMimicry(body, true);
        JsonNode content = JSON.readTree(result.body()).get("messages").get(0).get("content");

        assertTrue(content.isArray());
        assertEquals("text", content.get(0).get("type").asText());
        assertEquals("hello", content.get(0).get("text").asText());
        assertEquals(AnthropicClaudeCodeProfile.DEFAULT_CACHE_CONTROL_TTL,
                content.get(0).get("cache_control").get("ttl").asText());
    }

    @Test
    @DisplayName("post-normalize mimicry 动态改写超过 5 个自定义工具")
    void postNormalizeMimicryDynamicallyRewritesManyTools() throws Exception {
        String body = """
                {
                  "model":"claude-sonnet-4-5",
                  "tools":[
                    {"name":"alpha_tool","input_schema":{}},
                    {"name":"beta_tool","input_schema":{}},
                    {"name":"gamma_tool","input_schema":{}},
                    {"name":"delta_tool","input_schema":{}},
                    {"name":"epsilon_tool","input_schema":{}},
                    {"name":"zeta_tool","input_schema":{}}
                  ],
                  "messages":[{"role":"user","content":"Use tools"}]
                }""";

        OAuthMimicryService.ClaudeOAuthBodyMimicryResult result =
                service.applyPostNormalizeClaudeOAuthMimicry(body);
        JsonNode root = JSON.readTree(result.body());

        assertTrue(result.toolNameRewrite().hasRewrite());
        assertEquals(6, result.toolNameRewrite().forward().size());
        assertNotEquals("alpha_tool", root.get("tools").get(0).get("name").asText());
        assertTrue(result.toolNameRewrite().restore(root.get("tools").get(0).get("name").asText())
                .contains("alpha_tool"));
    }

    @Test
    @DisplayName("cache ttl 1h 注入只修改已有 ephemeral cache_control")
    void cacheTtl1hInjectionOnlyUpdatesExistingEphemeralCacheControl() throws Exception {
        String body = """
                {
                  "cache_control":{"type":"ephemeral"},
                  "system":[
                    {"type":"text","text":"sys","cache_control":{"type":"ephemeral","ttl":"5m"}},
                    {"type":"text","text":"plain"}
                  ],
                  "messages":[
                    {"role":"user","content":[
                      {"type":"text","text":"hi","cache_control":{"type":"ephemeral"}},
                      {"type":"text","text":"no","cache_control":{"type":"persistent","ttl":"5m"}}
                    ]}
                  ],
                  "tools":[
                    {"name":"a","input_schema":{},"cache_control":{"type":"ephemeral","ttl":"5m"}},
                    {"name":"b","input_schema":{}}
                  ]
                }""";

        JsonNode root = JSON.readTree(service.applyAnthropicCacheControlTTL1h(body, true));

        assertEquals(AnthropicClaudeCodeProfile.CACHE_CONTROL_TTL_1H,
                root.get("cache_control").get("ttl").asText());
        assertEquals(AnthropicClaudeCodeProfile.CACHE_CONTROL_TTL_1H,
                root.get("system").get(0).get("cache_control").get("ttl").asText());
        assertFalse(root.get("system").get(1).has("cache_control"));
        assertEquals(AnthropicClaudeCodeProfile.CACHE_CONTROL_TTL_1H,
                root.get("messages").get(0).get("content").get(0).get("cache_control").get("ttl").asText());
        assertEquals("5m",
                root.get("messages").get(0).get("content").get(1).get("cache_control").get("ttl").asText());
        assertEquals(AnthropicClaudeCodeProfile.CACHE_CONTROL_TTL_1H,
                root.get("tools").get(0).get("cache_control").get("ttl").asText());
        assertFalse(root.get("tools").get(1).has("cache_control"));
    }

    @Test
    @DisplayName("cache ttl 1h 注入默认关闭时不改 body")
    void cacheTtl1hInjectionKeepsBodyWhenDisabled() {
        String body = """
                {"messages":[{"role":"user","content":[{"type":"text","text":"hi","cache_control":{"type":"ephemeral","ttl":"5m"}}]}]}
                """.trim();

        assertEquals(body, service.applyAnthropicCacheControlTTL1h(body, false));
    }
}
