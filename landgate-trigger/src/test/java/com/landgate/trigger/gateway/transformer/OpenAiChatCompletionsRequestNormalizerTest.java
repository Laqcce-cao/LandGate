package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAiChatCompletionsRequestNormalizer 测试")
class OpenAiChatCompletionsRequestNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final OpenAiChatCompletionsRequestNormalizer normalizer = new OpenAiChatCompletionsRequestNormalizer();

    @Test
    @DisplayName("Chat Completions 请求应用账号 model_mapping")
    void chatCompletionsAppliesAccountModelMapping() throws Exception {
        AccountEntity account = AccountEntity.builder()
                .id(21L)
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .credentials("""
                        {"model_mapping":{
                          "gpt-*":"fallback",
                          "gpt-5.4*":"gpt-5.4-upstream"
                        }}
                        """)
                .build();

        String normalized = normalizer.normalize("""
                {"model":"gpt-5.4-mini","stream":false,"messages":[{"role":"user","content":"Hi"}]}
                """, account);

        JsonNode root = JSON.readTree(normalized);

        assertEquals("gpt-5.4-upstream", root.get("model").asText());
        assertFalse(root.has(OpenAiNormalizerProfile.FIELD_STREAM_OPTIONS));
    }

    @Test
    @DisplayName("stream=true 且缺失 stream_options 时补 include_usage=true")
    void streamingRequestAddsIncludeUsageWhenMissing() throws Exception {
        String normalized = normalizer.normalize("""
                {"model":"gpt-5.5","stream":true,"messages":[{"role":"user","content":"Hi"}]}
                """);

        JsonNode root = JSON.readTree(normalized);

        assertTrue(root.get(OpenAiNormalizerProfile.FIELD_STREAM_OPTIONS)
                .get(OpenAiNormalizerProfile.FIELD_INCLUDE_USAGE)
                .asBoolean());
    }

    @Test
    @DisplayName("stream=true 且 include_usage=false 时强制改为 true")
    void streamingRequestForcesIncludeUsageTrue() throws Exception {
        String normalized = normalizer.normalize("""
                {
                  "model":"gpt-5.5",
                  "stream":true,
                  "stream_options":{"include_usage":false},
                  "messages":[{"role":"user","content":"Hi"}]
                }
                """);

        JsonNode root = JSON.readTree(normalized);

        assertTrue(root.get(OpenAiNormalizerProfile.FIELD_STREAM_OPTIONS)
                .get(OpenAiNormalizerProfile.FIELD_INCLUDE_USAGE)
                .asBoolean());
    }

    @Test
    @DisplayName("非流式请求不新增 stream_options")
    void nonStreamingRequestIsUnchanged() throws Exception {
        String normalized = normalizer.normalize("""
                {"model":"gpt-5.5","stream":false,"messages":[{"role":"user","content":"Hi"}]}
                """);

        JsonNode root = JSON.readTree(normalized);

        assertFalse(root.has(OpenAiNormalizerProfile.FIELD_STREAM_OPTIONS));
    }

    @Test
    @DisplayName("Raw Chat 默认 fast policy 过滤 priority 并保留 flex")
    void chatCompletionsDefaultFastPolicyFiltersPriorityOnly() throws Exception {
        String priority = normalizer.normalize("""
                {"model":"gpt-5.5","service_tier":"fast","messages":[{"role":"user","content":"Hi"}]}
                """, apiKeyAccount());
        JsonNode priorityRoot = JSON.readTree(priority);
        assertFalse(priorityRoot.has(OpenAiNormalizerProfile.FIELD_SERVICE_TIER));

        String flex = normalizer.normalize("""
                {"model":"gpt-5.5","service_tier":"flex","messages":[{"role":"user","content":"Hi"}]}
                """, apiKeyAccount());
        JsonNode flexRoot = JSON.readTree(flex);
        assertEquals("flex", flexRoot.get(OpenAiNormalizerProfile.FIELD_SERVICE_TIER).asText());
    }

    @Test
    @DisplayName("Raw Chat 自定义 fast policy block 返回明确异常")
    void chatCompletionsConfiguredFastPolicyBlocksRequest() {
        OpenAiChatCompletionsRequestNormalizer configured = new OpenAiChatCompletionsRequestNormalizer(
                new OpenAiFastPolicyProvider("""
                        {"rules":[{"service_tier":"priority","action":"block","scope":"apikey",
                          "error_message":"raw chat fast blocked","model_whitelist":["gpt-5.5"]}]}
                        """));

        OpenAiFastPolicyBlockedException blocked = assertThrows(
                OpenAiFastPolicyBlockedException.class,
                () -> configured.normalize("""
                        {"model":"gpt-5.5","service_tier":"fast","messages":[{"role":"user","content":"Hi"}]}
                        """, apiKeyAccount()));

        assertEquals("raw chat fast blocked", blocked.getMessage());
        assertEquals("priority", blocked.tier());
        assertEquals("gpt-5.5", blocked.model());
    }

    private static AccountEntity apiKeyAccount() {
        return AccountEntity.builder()
                .id(22L)
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .credentials("{}")
                .build();
    }
}
