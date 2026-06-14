package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.OpenAiAnthropicMessagesCompatPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenAiCodexBodyNormalizer tests")
class OpenAiCodexBodyNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final OpenAiCodexBodyNormalizer normalizer = new OpenAiCodexBodyNormalizer();

    @Test
    @DisplayName("Codex 普通请求先应用账号 model_mapping 再做 Codex 模型规范化")
    void accountModelMappingRunsBeforeCodexModelNormalization() throws Exception {
        AccountEntity account = AccountEntity.builder()
                .id(16L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .credentials("""
                        {"model_mapping":{"gpt-5":"gpt-5.3"}}
                        """)
                .build();

        String normalized = normalizer.normalize("""
                {
                  "model":"gpt-5",
                  "input":"Hi",
                  "stream":false
                }
                """, account, codexRoute(), "req-codex-model-mapping", true);

        JsonNode root = JSON.readTree(normalized);

        assertEquals("gpt-5.3-codex", root.get("model").asText());
        assertEquals(OpenAiNormalizerProfile.DEFAULT_CODEX_INSTRUCTIONS, root.get("instructions").asText());
    }

    @Test
    @DisplayName("Codex compact mapping has priority over OAuth model normalization")
    void compactMappingHasPriorityOverCodexModelNormalization() throws Exception {
        AccountEntity account = AccountEntity.builder()
                .id(15L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .credentials("""
                        {"compact_model_mapping":{"gpt-5.4":"gpt-5.4-openai-compact"}}
                        """)
                .build();

        String normalized = normalizer.normalize("""
                {
                  "model":"gpt-5.4",
                  "input":"Compact me",
                  "store":true,
                  "stream":true,
                  "prompt_cache_key":"tenant:thread"
                }
                """, account, compactRoute(), "req-compact-codex", true);

        JsonNode root = JSON.readTree(normalized);

        assertEquals("gpt-5.4-openai-compact", root.get("model").asText());
        assertFalse(root.has("store"));
        assertFalse(root.has("stream"));
        assertFalse(root.has("prompt_cache_key"));
    }

    @Test
    @DisplayName("Codex OAuth messages compat 为 Codex 模型插入 Claude Code todo guard")
    void messagesCompatAddsTodoGuardForCodexModel() throws Exception {
        AccountEntity account = AccountEntity.builder()
                .id(17L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .credentials("{}")
                .build();

        String normalized = normalizer.normalize("""
                {
                  "model":"gpt-5.4",
                  "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"hello"}]}],
                  "stream":false
                }
                """, account, codexMessagesRoute(), "req-codex-messages-guard", true);

        JsonNode input = JSON.readTree(normalized).get("input");

        assertEquals("developer", input.get(0).get("role").asText());
        assertTrue(input.get(0).get("content").get(0).get("text").asText()
                .contains(OpenAiAnthropicMessagesCompatPolicy.TODO_GUARD_MARKER));
        assertEquals("user", input.get(1).get("role").asText());
    }

    @Test
    @DisplayName("Codex OAuth messages compat applies forced instructions template after system extraction")
    void messagesCompatAppliesForcedInstructionsTemplate() throws Exception {
        OpenAiCodexBodyNormalizer templatedNormalizer =
                new OpenAiCodexBodyNormalizer(() -> "server-prefix\n\n{{ .ExistingInstructions }}");
        AccountEntity account = AccountEntity.builder()
                .id(18L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .credentials("{}")
                .build();

        String normalized = templatedNormalizer.normalize("""
                {
                  "model":"gpt-5.4",
                  "input":[
                    {"type":"message","role":"developer","content":[{"type":"input_text","text":"client-system"}]},
                    {"type":"message","role":"user","content":[{"type":"input_text","text":"hello"}]}
                  ],
                  "stream":false
                }
                """, account, codexMessagesRoute(), "req-codex-forced-instructions", true);

        JsonNode root = JSON.readTree(normalized);

        assertEquals("server-prefix\n\nclient-system", root.get("instructions").asText());
        assertEquals("developer", root.get("input").get(0).get("role").asText());
        assertTrue(root.get("input").get(0).get("content").get(0).get("text").asText()
                .contains(OpenAiAnthropicMessagesCompatPolicy.TODO_GUARD_MARKER));
        assertEquals("user", root.get("input").get(1).get("role").asText());
    }

    @Test
    @DisplayName("Codex OAuth 默认 fast policy 过滤 priority service_tier 并保留 flex")
    void codexDefaultFastPolicyFiltersPriorityServiceTierOnly() throws Exception {
        AccountEntity account = AccountEntity.builder()
                .id(19L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .credentials("{}")
                .build();

        String priority = normalizer.normalize("""
                {
                  "model":"gpt-5.4",
                  "input":"Hi",
                  "service_tier":"fast"
                }
                """, account, codexRoute(), "req-codex-fast-filter", true);
        JsonNode priorityRoot = JSON.readTree(priority);

        assertFalse(priorityRoot.has(OpenAiNormalizerProfile.FIELD_SERVICE_TIER));

        String flex = normalizer.normalize("""
                {
                  "model":"gpt-5.4",
                  "input":"Hi",
                  "service_tier":"flex"
                }
                """, account, codexRoute(), "req-codex-flex-pass", true);
        JsonNode flexRoot = JSON.readTree(flex);

        assertEquals("flex", flexRoot.get(OpenAiNormalizerProfile.FIELD_SERVICE_TIER).asText());
    }

    @Test
    @DisplayName("Codex OAuth 自定义 fast policy block 返回明确异常")
    void codexConfiguredFastPolicyBlocksRequest() {
        OpenAiCodexBodyNormalizer configured = new OpenAiCodexBodyNormalizer(
                () -> "",
                new OpenAiFastPolicyProvider("""
                        {"rules":[{"service_tier":"priority","action":"block","scope":"oauth",
                          "error_message":"codex fast blocked","model_whitelist":["gpt-5.4*"]}]}
                        """));
        AccountEntity account = AccountEntity.builder()
                .id(22L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .credentials("{}")
                .build();

        OpenAiFastPolicyBlockedException blocked = assertThrows(
                OpenAiFastPolicyBlockedException.class,
                () -> configured.normalize("""
                        {
                          "model":"gpt-5.4",
                          "input":"Hi",
                          "service_tier":"fast"
                        }
                        """, account, codexRoute(), "req-codex-fast-block", true));

        assertEquals("codex fast blocked", blocked.getMessage());
        assertEquals("priority", blocked.tier());
        assertTrue(blocked.model().startsWith("gpt-5.4"));
    }

    @Test
    @DisplayName("Codex OAuth tool continuation preserves item references and normalizes call ids")
    void codexToolContinuationPreservesReferencesAndNormalizesCallIds() throws Exception {
        AccountEntity account = AccountEntity.builder()
                .id(23L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .credentials("{}")
                .build();

        String normalized = normalizer.normalize("""
                {
                  "model":"gpt-5.4",
                  "previous_response_id":"resp_1",
                  "input":[
                    {"type":"item_reference","id":"call_1"},
                    {"type":"function_call_output","call_id":"call_1","output":"ok"},
                    {"type":"message","role":"assistant","id":"msg_1","call_id":"call_noise","content":"done"}
                  ]
                }
                """, account, codexRoute(), "req-codex-tool-continuation", true);

        JsonNode input = JSON.readTree(normalized).get("input");

        assertEquals("item_reference", input.get(0).get("type").asText());
        assertEquals("fc1", input.get(0).get("id").asText());
        assertEquals("function_call_output", input.get(1).get("type").asText());
        assertEquals("fc1", input.get(1).get("call_id").asText());
        assertEquals("message", input.get(2).get("type").asText());
        assertEquals("msg_1", input.get(2).get("id").asText());
        assertFalse(input.get(2).has("call_id"));
    }

    @Test
    @DisplayName("Codex OAuth non-continuation input drops ordinary replay ids")
    void codexNonContinuationDropsOrdinaryIds() throws Exception {
        AccountEntity account = AccountEntity.builder()
                .id(24L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .credentials("{}")
                .build();

        String normalized = normalizer.normalize("""
                {
                  "model":"gpt-5.4",
                  "input":[
                    {"type":"message","role":"user","id":"msg_1","content":"Hi"}
                  ]
                }
                """, account, codexRoute(), "req-codex-non-continuation", true);

        JsonNode input = JSON.readTree(normalized).get("input");

        assertEquals(1, input.size());
        assertEquals("message", input.get(0).get("type").asText());
        assertFalse(input.get(0).has("id"));
    }

    @Test
    @DisplayName("Codex OAuth messages compat keeps Anthropic call ids during tool continuation")
    void codexMessagesCompatPreservesAnthropicCallIdsDuringContinuation() throws Exception {
        AccountEntity account = AccountEntity.builder()
                .id(25L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .credentials("{}")
                .build();

        String normalized = normalizer.normalize("""
                {
                  "model":"gpt-5.4",
                  "previous_response_id":"resp_1",
                  "input":[
                    {"type":"item_reference","id":"call_1"},
                    {"type":"function_call_output","call_id":"call_1","output":"ok"}
                  ]
                }
                """, account, codexMessagesRoute(), "req-codex-messages-tool-continuation", true);

        JsonNode input = JSON.readTree(normalized).get("input");

        assertEquals("item_reference", input.get(1).get("type").asText());
        assertEquals("call_1", input.get(1).get("id").asText());
        assertEquals("function_call_output", input.get(2).get("type").asText());
        assertEquals("call_1", input.get(2).get("call_id").asText());
    }

    private static UpstreamRoute compactRoute() {
        return new UpstreamRoute(
                Platform.OPENAI,
                "responses",
                "responses",
                EndpointKind.OPENAI_CODEX_RESPONSES,
                "https://chatgpt.com/backend-api/codex/responses/compact",
                false,
                true,
                "responses",
                "openai_oauth_codex");
    }

    private static UpstreamRoute codexRoute() {
        return new UpstreamRoute(
                Platform.OPENAI,
                "responses",
                "responses",
                EndpointKind.OPENAI_CODEX_RESPONSES,
                "https://chatgpt.com/backend-api/codex/responses",
                false,
                true,
                "responses",
                "openai_oauth_codex");
    }

    private static UpstreamRoute codexMessagesRoute() {
        return new UpstreamRoute(
                Platform.OPENAI,
                "messages",
                "responses",
                EndpointKind.OPENAI_CODEX_RESPONSES,
                "https://chatgpt.com/backend-api/codex/responses",
                true,
                true,
                "responses",
                "openai_oauth_codex");
    }
}
