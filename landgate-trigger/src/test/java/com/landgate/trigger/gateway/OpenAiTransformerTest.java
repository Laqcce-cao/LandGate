package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAiTransformer 单元测试 —— 验证 OpenAI 上游请求构建和 OAuth Codex 专用请求规范化。
 */
@DisplayName("OpenAiTransformer 测试")
class OpenAiTransformerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("构建请求时优先使用 GatewayRequestContext 中的上游路由地址")
    void buildRequestUsesResolvedUpstreamRouteTargetUrl() {
        OpenAiTransformer transformer = new OpenAiTransformer();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .build();
        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                "{}",
                account,
                "token-1",
                new UpstreamRoute(
                        Platform.OPENAI,
                        "responses",
                        "chat_completions",
                        EndpointKind.OPENAI_CHAT_COMPLETIONS,
                        "https://proxy.example.com/v1/chat/completions",
                        false,
                        false,
                        false,
                        "chat_completions",
                        "test_route"),
                null,
                null,
                null,
                false,
                false,
                Map.of()));

        assertEquals("https://proxy.example.com/v1/chat/completions", request.uri().toString());
    }

    @Test
    @DisplayName("API Key Chat Completions 流式请求强制携带 include_usage")
    void apiKeyChatCompletionsStreamingRequestForcesIncludeUsage() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .build();
        String body = """
                {
                  "model":"gpt-5.5",
                  "stream":true,
                  "stream_options":{"include_usage":false},
                  "messages":[{"role":"user","content":"Hi"}]
                }""";

        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                body,
                account,
                "token-1",
                new UpstreamRoute(
                        Platform.OPENAI,
                        "messages",
                        "chat_completions",
                        EndpointKind.OPENAI_CHAT_COMPLETIONS,
                        "https://proxy.example.com/v1/chat/completions",
                        false,
                        false,
                        false,
                        "chat_completions",
                        "test_route"),
                null,
                null,
                null,
                false,
                false,
                Map.of()));

        JsonNode root = JSON.readTree(readBody(request));

        assertTrue(root.get("stream_options").get("include_usage").asBoolean());
    }

    @Test
    @DisplayName("OAuth Codex 请求将 developer 输入移入 instructions")
    void codexOAuthRequestMovesDeveloperInputToInstructions() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer();
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
        assertEquals("Project rules", root.get("instructions").asText());
        assertEquals(1, root.get("input").size());
        assertEquals("user", root.get("input").get(0).get("role").asText());
    }

    @Test
    @DisplayName("OAuth Codex compact 请求移除不支持字段")
    void codexOAuthCompactRequestRemovesUnsupportedFields() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .build();
        String body = """
                {
                  "model":"gpt-5.5",
                  "input":[{"role":"user","content":"Hi"}],
                  "store":true,
                  "stream":false
                }""";

        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                body,
                account,
                "token-1",
                new UpstreamRoute(
                        Platform.OPENAI,
                        "responses",
                        "responses",
                        EndpointKind.OPENAI_CODEX_RESPONSES,
                        "https://chatgpt.com/backend-api/codex/responses/compact",
                        false,
                        true,
                        true,
                        "responses",
                        "openai_oauth_codex"),
                null,
                "/v1/responses/compact",
                "gpt-5.5",
                true,
                false,
                Map.of()));

        JsonNode root = JSON.readTree(readBody(request));

        assertFalse(root.has("store"));
        assertFalse(root.has("stream"));
    }

    @Test
    @DisplayName("OAuth Codex 请求强制上游流式")
    void codexOAuthRequestForcesUpstreamStreaming() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .build();
        Method method = OpenAiTransformer.class.getDeclaredMethod(
                "normalizeCodexOAuthRequestBody", String.class, AccountEntity.class);
        method.setAccessible(true);

        String streamTrue = (String) method.invoke(transformer,
                "{\"model\":\"gpt-5.5\",\"input\":[],\"stream\":true}", account);
        String streamFalse = (String) method.invoke(transformer,
                "{\"model\":\"gpt-5.5\",\"input\":[],\"stream\":false}", account);
        String streamMissing = (String) method.invoke(transformer,
                "{\"model\":\"gpt-5.5\",\"input\":[]}", account);

        assertTrue(JSON.readTree(streamTrue).get("stream").asBoolean());
        assertTrue(JSON.readTree(streamFalse).get("stream").asBoolean());
        assertTrue(JSON.readTree(streamMissing).get("stream").asBoolean());
    }

    private static String readBody(HttpRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        assertTrue(done.await(1, TimeUnit.SECONDS));
        if (error.get() != null) throw new AssertionError(error.get());
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("OAuth Codex 请求将 system 输入移入 instructions")
    void codexOAuthRequestMovesSystemInputToInstructions() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer();
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
