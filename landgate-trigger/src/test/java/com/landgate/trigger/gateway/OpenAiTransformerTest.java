package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAiTransformer 单元测试 —— 验证 OpenAI 上游请求构建和 OAuth Codex 专用请求规范化。
 */
@DisplayName("OpenAiTransformer 测试")
class OpenAiTransformerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("OAuth Codex 请求移除不支持字段并补齐 instructions")
    void codexOAuthRequestRemovesUnsupportedFieldsAndAddsInstructions() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer(new UpstreamCapabilityService());
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .build();
        String body = """
                {
                  "model":"gpt-5.5",
                  "max_output_tokens":128,
                  "temperature":0.7,
                  "stream_options":{"include_usage":true},
                  "input":[
                    {"type":"message","role":"developer","content":[{"type":"input_text","text":"Project rules"}]},
                    {"type":"message","role":"user","content":[{"type":"input_text","text":"Hi"}]}
                  ],
                  "store":true,
                  "stream":false
                }""";

        Method method = OpenAiTransformer.class.getDeclaredMethod(
                "normalizeCodexOAuthRequestBody", String.class, AccountEntity.class);
        method.setAccessible(true);
        String normalized = (String) method.invoke(transformer, body, account);
        JsonNode root = JSON.readTree(normalized);

        assertFalse(root.has("max_output_tokens"));
        assertFalse(root.has("temperature"));
        assertFalse(root.has("stream_options"));
        assertFalse(root.get("store").asBoolean());
        assertTrue(root.get("stream").asBoolean());
        assertEquals("You are a helpful coding assistant.", root.get("instructions").asText());
    }

    @Test
    @DisplayName("OAuth Codex 请求将 system 输入移入 instructions")
    void codexOAuthRequestMovesSystemInputToInstructions() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer(new UpstreamCapabilityService());
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .build();
        String body = """
                {
                  "model":"gpt-5.5",
                  "input":[
                    {"role":"system","content":"System rules"},
                    {"role":"user","content":"Hi"}
                  ]
                }""";

        Method method = OpenAiTransformer.class.getDeclaredMethod(
                "normalizeCodexOAuthRequestBody", String.class, AccountEntity.class);
        method.setAccessible(true);
        String normalized = (String) method.invoke(transformer, body, account);
        JsonNode root = JSON.readTree(normalized);

        assertEquals("System rules", root.get("instructions").asText());
        assertEquals(1, root.get("input").size());
        assertEquals("user", root.get("input").get(0).get("role").asText());
    }
}
