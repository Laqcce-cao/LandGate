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
