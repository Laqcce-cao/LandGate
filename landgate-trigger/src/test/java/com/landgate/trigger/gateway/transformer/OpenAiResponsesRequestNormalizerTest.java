package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAiResponsesRequestNormalizer 测试")
class OpenAiResponsesRequestNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final OpenAiResponsesRequestNormalizer normalizer = new OpenAiResponsesRequestNormalizer();

    @Test
    @DisplayName("Public Responses 清理不支持字段并按默认 fast policy 过滤 priority service_tier")
    void normalizesPublicResponsesCompatibilityFields() throws Exception {
        String normalized = normalizer.normalize("""
                {
                  "model":"gpt-5.5",
                  "service_tier":"fast",
                  "previous_response_id":"resp_internal",
                  "reasoning":{"effort":"minimal"},
                  "max_output_tokens":256,
                  "max_completion_tokens":128,
                  "prompt_cache_retention":"24h",
                  "safety_identifier":"safe_user_1",
                  "prompt_cache_key":"tenant:thread",
                  "input":"Hi"
                }
                """);

        JsonNode root = JSON.readTree(normalized);

        assertFalse(root.has(OpenAiNormalizerProfile.FIELD_SERVICE_TIER));
        assertEquals("none", root.get(OpenAiResponsesBodyPolicy.FIELD_REASONING)
                .get(OpenAiResponsesBodyPolicy.FIELD_EFFORT).asText());
        assertFalse(root.has(OpenAiNormalizerProfile.FIELD_MAX_OUTPUT_TOKENS));
        assertFalse(root.has(OpenAiNormalizerProfile.FIELD_MAX_COMPLETION_TOKENS));
        assertFalse(root.has(OpenAiNormalizerProfile.FIELD_PROMPT_CACHE_RETENTION));
        assertFalse(root.has(OpenAiNormalizerProfile.FIELD_SAFETY_IDENTIFIER));
        assertEquals("tenant:thread", root.get(OpenAiNormalizerProfile.FIELD_PROMPT_CACHE_KEY).asText());
        assertEquals("resp_internal", root.get(OpenAiResponsesBodyPolicy.FIELD_PREVIOUS_RESPONSE_ID).asText());
    }

    @Test
    @DisplayName("Public Responses 默认 fast policy 保留官方非 priority service_tier")
    void publicResponsesPreservesOfficialNonPriorityServiceTiers() throws Exception {
        for (String tier : java.util.List.of("flex", "auto", "default", "scale")) {
            String normalized = normalizer.normalize("""
                    {"model":"gpt-5.5","service_tier":"%s","input":"Hi"}
                    """.formatted(tier));

            JsonNode root = JSON.readTree(normalized);

            assertEquals(tier, root.get(OpenAiNormalizerProfile.FIELD_SERVICE_TIER).asText(), tier);
        }
    }

    @Test
    @DisplayName("Public Responses 未知 service_tier 被移除")
    void publicResponsesDropsUnknownServiceTier() throws Exception {
        String normalized = normalizer.normalize("""
                {"model":"gpt-5.5","service_tier":"turbo","input":"Hi"}
                """);

        JsonNode root = JSON.readTree(normalized);

        assertFalse(root.has(OpenAiNormalizerProfile.FIELD_SERVICE_TIER));
    }

    @Test
    @DisplayName("Public Responses 自定义 fast policy 可过滤所有官方 service_tier")
    void publicResponsesConfiguredPolicyFiltersAllRecognizedServiceTiers() throws Exception {
        OpenAiResponsesRequestNormalizer configured = new OpenAiResponsesRequestNormalizer(
                new OpenAiFastPolicyProvider("""
                        {"rules":[{"service_tier":"all","action":"filter","scope":"all"}]}
                        """));
        AccountEntity account = AccountEntity.builder()
                .id(20L)
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .credentials("{}")
                .build();

        String normalized = configured.normalize("""
                {"model":"gpt-5.5","service_tier":"flex","input":"Hi"}
                """, responsesRoute(), account);

        JsonNode root = JSON.readTree(normalized);
        assertFalse(root.has(OpenAiNormalizerProfile.FIELD_SERVICE_TIER));
    }

    @Test
    @DisplayName("Public Responses 自定义 fast policy block 返回明确异常")
    void publicResponsesConfiguredPolicyBlocksRequest() {
        OpenAiResponsesRequestNormalizer configured = new OpenAiResponsesRequestNormalizer(
                new OpenAiFastPolicyProvider("""
                        {"rules":[{"service_tier":"priority","action":"block","scope":"apikey",
                          "error_message":"fast mode is not allowed","model_whitelist":["gpt-5.5"]}]}
                        """));
        AccountEntity account = AccountEntity.builder()
                .id(21L)
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .credentials("{}")
                .build();

        OpenAiFastPolicyBlockedException blocked = assertThrows(
                OpenAiFastPolicyBlockedException.class,
                () -> configured.normalize("""
                        {"model":"gpt-5.5","service_tier":"fast","input":"Hi"}
                        """, responsesRoute(), account));

        assertEquals("fast mode is not allowed", blocked.getMessage());
        assertEquals("priority", blocked.tier());
        assertEquals("gpt-5.5", blocked.model());
    }

    @Test
    @DisplayName("Public Responses 清理空 base64 input_image")
    void dropsEmptyBase64InputImageParts() throws Exception {
        String normalized = normalizer.normalize("""
                {
                  "model":"gpt-5.5",
                  "input":[
                    {"type":"input_image","image_url":"data:image/png;base64,"},
                    {"type":"message","role":"user","content":[
                      {"type":"input_text","text":"Hi"},
                      {"type":"input_image","image_url":"data:image/png;base64,   "}
                    ]}
                  ]
                }
                """);

        JsonNode input = JSON.readTree(normalized).get(OpenAiNormalizerProfile.FIELD_INPUT);

        assertEquals(1, input.size());
        assertEquals(1, input.get(0).get(OpenAiNormalizerProfile.FIELD_CONTENT).size());
        assertEquals("input_text", input.get(0).get(OpenAiNormalizerProfile.FIELD_CONTENT).get(0)
                .get(OpenAiNormalizerProfile.FIELD_TYPE).asText());
    }

    @Test
    @DisplayName("Public Responses 普通请求应用账号 model_mapping")
    void publicResponsesAppliesAccountModelMapping() throws Exception {
        AccountEntity account = AccountEntity.builder()
                .id(11L)
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
                {"model":"gpt-5.4-mini","input":"Hi"}
                """, responsesRoute(), account);

        JsonNode root = JSON.readTree(normalized);

        assertEquals("gpt-5.4-upstream", root.get("model").asText());
    }

    @Test
    @DisplayName("Public Responses 普通请求不使用 compact-only model mapping")
    void publicResponsesDoesNotUseCompactOnlyModelMapping() throws Exception {
        AccountEntity account = AccountEntity.builder()
                .id(13L)
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .credentials("""
                        {"compact_model_mapping":{"gpt-5.4":"gpt-5.4-openai-compact"}}
                        """)
                .build();

        String normalized = normalizer.normalize("""
                {"model":"gpt-5.4","input":"Hi"}
                """, responsesRoute(), account);

        JsonNode root = JSON.readTree(normalized);

        assertEquals("gpt-5.4", root.get("model").asText());
    }

    @Test
    @DisplayName("Public Responses compact 请求按 sub2api compact schema 白名单清理")
    void compactRequestKeepsOnlyCompactSchemaFields() throws Exception {
        String normalized = normalizer.normalize("""
                {
                  "model":"gpt-5.5",
                  "input":"Compact me",
                  "instructions":"Keep concise",
                  "tools":[],
                  "parallel_tool_calls":true,
                  "reasoning":{"effort":"low"},
                  "text":{"verbosity":"medium"},
                  "previous_response_id":"resp_prev",
                  "prompt_cache_key":"tenant:thread",
                  "store":true,
                  "stream":true,
                  "service_tier":"fast",
                  "max_completion_tokens":128
                }
                """, compactRoute());

        JsonNode root = JSON.readTree(normalized);

        assertEquals("gpt-5.5", root.get("model").asText());
        assertEquals("Compact me", root.get("input").asText());
        assertEquals("Keep concise", root.get("instructions").asText());
        assertTrue(root.get("parallel_tool_calls").asBoolean());
        assertEquals("resp_prev", root.get("previous_response_id").asText());
        assertFalse(root.has("prompt_cache_key"));
        assertFalse(root.has("store"));
        assertFalse(root.has("stream"));
        assertFalse(root.has("service_tier"));
        assertFalse(root.has("max_completion_tokens"));
    }

    @Test
    @DisplayName("Public Responses compact 请求应用账号 compact-only model mapping")
    void compactRequestAppliesAccountModelMapping() throws Exception {
        AccountEntity account = AccountEntity.builder()
                .id(12L)
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .credentials("""
                        {"compact_model_mapping":{
                          "gpt-*":"fallback-compact",
                          "gpt-5.4*":"gpt-5.4-openai-compact",
                          "gpt-5.4-mini*":"gpt-5.4-mini-openai-compact"
                        }}
                        """)
                .build();

        String normalized = normalizer.normalize("""
                {
                  "model":"gpt-5.4-mini",
                  "input":"Compact me",
                  "store":true
                }
                """, compactRoute(), account);

        JsonNode root = JSON.readTree(normalized);

        assertEquals("gpt-5.4-mini-openai-compact", root.get("model").asText());
        assertFalse(root.has("store"));
    }

    private static UpstreamRoute compactRoute() {
        return new UpstreamRoute(
                Platform.OPENAI,
                "responses",
                "responses",
                EndpointKind.OPENAI_RESPONSES,
                "https://api.openai.com/v1/responses/compact",
                false,
                false,
                "responses",
                "openai_api_key_responses");
    }

    private static UpstreamRoute responsesRoute() {
        return new UpstreamRoute(
                Platform.OPENAI,
                "responses",
                "responses",
                EndpointKind.OPENAI_RESPONSES,
                "https://api.openai.com/v1/responses",
                false,
                false,
                "responses",
                "openai_api_key_responses");
    }
}
