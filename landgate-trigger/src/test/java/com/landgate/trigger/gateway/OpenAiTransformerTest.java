package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.trigger.gateway.transformer.OpenAiTransformer;
import com.landgate.trigger.gateway.transformer.UpstreamRequestContext;
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
    @DisplayName("API Key raw Chat Completions 仅透传 sub2api 白名单 Header")
    void apiKeyChatCompletionsForwardsOnlyRawChatAllowedHeaders() {
        OpenAiTransformer transformer = new OpenAiTransformer();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .build();

        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                "{\"model\":\"gpt-5.5\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}",
                account,
                "token-1",
                new UpstreamRoute(
                        Platform.OPENAI,
                        "chat_completions",
                        "chat_completions",
                        EndpointKind.OPENAI_CHAT_COMPLETIONS,
                        "https://proxy.example.com/v1/chat/completions",
                        true,
                        false,
                        false,
                        "chat_completions",
                        "test_route"),
                null,
                null,
                null,
                true,
                false,
                Map.of(
                        "User-Agent", "openai-sdk-java/1.0",
                        "Accept-Language", "zh-CN",
                        "OpenAI-Beta", "responses=experimental",
                        "originator", "codex_cli_rs",
                        "session_id", "sess_1",
                        "x-codex-turn-state", "turn_state")));

        assertEquals("text/event-stream", request.headers().firstValue("Accept").orElse(""));
        assertEquals("openai-sdk-java/1.0", request.headers().firstValue("User-Agent").orElse(""));
        assertEquals("zh-CN", request.headers().firstValue("Accept-Language").orElse(""));
        assertTrue(request.headers().firstValue("OpenAI-Beta").isEmpty());
        assertEquals("codex_cli_rs", request.headers().firstValue("originator").orElse(""));
        assertEquals("sess_1", request.headers().firstValue("session_id").orElse(""));
        assertEquals("turn_state", request.headers().firstValue("x-codex-turn-state").orElse(""));
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
                  "max_completion_tokens":256,
                  "temperature":0.7,
                  "top_p":0.9,
                  "frequency_penalty":0.2,
                  "presence_penalty":0.3,
                  "service_tier":"flex",
                  "metadata":{"trace_id":"req_1"},
                  "user":"user_1",
                  "prompt_cache_key":"tenant:thread",
                  "prompt_cache_retention":"24h",
                  "safety_identifier":"safe_user_1",
                  "top_logprobs":3,
                  "stream_options":{"include_usage":true},
                  "include":["message.output_text.logprobs","web_search_call.results"],
                  "previous_response_id":"resp_prev",
                  "truncation":"auto",
                  "prompt":{"id":"pmpt_1"},
                  "background":true,
                  "conversation":{"id":"conv_1"},
                  "context_management":[{"type":"auto"}],
                  "parallel_tool_calls":true,
                  "max_tool_calls":4,
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
        assertFalse(root.has("max_completion_tokens"));
        assertFalse(root.has("temperature"));
        assertFalse(root.has("top_p"));
        assertFalse(root.has("frequency_penalty"));
        assertFalse(root.has("presence_penalty"));
        assertEquals("flex", root.get("service_tier").asText());
        assertFalse(root.has("metadata"));
        assertFalse(root.has("user"));
        assertEquals("tenant:thread", root.get("prompt_cache_key").asText());
        assertFalse(root.has("prompt_cache_retention"));
        assertFalse(root.has("safety_identifier"));
        assertEquals(3, root.get("top_logprobs").asInt());
        assertFalse(root.has("stream_options"));
        assertEquals("message.output_text.logprobs", root.get("include").get(0).asText());
        assertEquals("resp_prev", root.get("previous_response_id").asText());
        assertEquals("auto", root.get("truncation").asText());
        assertEquals("pmpt_1", root.get("prompt").get("id").asText());
        assertTrue(root.get("background").asBoolean());
        assertEquals("conv_1", root.get("conversation").get("id").asText());
        assertEquals("auto", root.get("context_management").get(0).get("type").asText());
        assertTrue(root.get("parallel_tool_calls").asBoolean());
        assertEquals(4, root.get("max_tool_calls").asInt());
        assertFalse(root.get("store").asBoolean());
        assertTrue(root.get("stream").asBoolean());
        assertEquals("Project rules", root.get("instructions").asText());
        assertEquals(1, root.get("input").size());
        assertEquals("user", root.get("input").get(0).get("role").asText());
    }

    @Test
    @DisplayName("OAuth Codex 默认保留 prompt_cache_key")
    void codexOAuthPreservesPromptCacheKeyByDefault() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .build();
        String body = """
                {
                  "model":"gpt-5.5",
                  "prompt_cache_key":"tenant:thread",
                  "prompt_cache_retention":"24h",
                  "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"Hi"}]}]
                }""";

        Method method = OpenAiTransformer.class.getDeclaredMethod(
                "normalizeCodexOAuthRequestBody", String.class, AccountEntity.class);
        method.setAccessible(true);
        String normalized = (String) method.invoke(transformer, body, account);
        JsonNode root = JSON.readTree(normalized);

        assertEquals("tenant:thread", root.get("prompt_cache_key").asText());
        assertFalse(root.has("prompt_cache_retention"));
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
                  "include":["message.output_text.logprobs"],
                  "previous_response_id":"resp_prev",
                  "truncation":"auto",
                  "prompt":{"id":"pmpt_1"},
                  "background":true,
                  "conversation":{"id":"conv_1"},
                  "context_management":[{"type":"auto"}],
                  "metadata":{"trace_id":"req_1"},
                  "user":"user_1",
                  "safety_identifier":"safe_user_1",
                  "prompt_cache_key":"tenant:thread",
                  "prompt_cache_retention":"24h",
                  "parallel_tool_calls":true,
                  "max_tool_calls":4,
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
        assertEquals("message.output_text.logprobs", root.get("include").get(0).asText());
        assertEquals("resp_prev", root.get("previous_response_id").asText());
        assertEquals("auto", root.get("truncation").asText());
        assertEquals("pmpt_1", root.get("prompt").get("id").asText());
        assertTrue(root.get("background").asBoolean());
        assertEquals("conv_1", root.get("conversation").get("id").asText());
        assertEquals("auto", root.get("context_management").get(0).get("type").asText());
        assertFalse(root.has("metadata"));
        assertFalse(root.has("user"));
        assertFalse(root.has("safety_identifier"));
        assertEquals("tenant:thread", root.get("prompt_cache_key").asText());
        assertFalse(root.has("prompt_cache_retention"));
        assertTrue(root.get("parallel_tool_calls").asBoolean());
        assertEquals(4, root.get("max_tool_calls").asInt());
        assertEquals("application/json", request.headers().firstValue("Accept").orElse(""));
        assertEquals("0.125.0", request.headers().firstValue("Version").orElse(""));
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

    @Test
    @DisplayName("OAuth Codex 请求按 sub2api 归一化 service_tier")
    void codexOAuthRequestNormalizesServiceTier() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .build();
        Method method = OpenAiTransformer.class.getDeclaredMethod(
                "normalizeCodexOAuthRequestBody", String.class, AccountEntity.class);
        method.setAccessible(true);

        JsonNode fast = JSON.readTree((String) method.invoke(transformer,
                "{\"model\":\"gpt-5.5\",\"service_tier\":\" fast \",\"input\":[]}", account));
        JsonNode unknown = JSON.readTree((String) method.invoke(transformer,
                "{\"model\":\"gpt-5.5\",\"service_tier\":\"turbo\",\"input\":[]}", account));

        assertEquals("priority", fast.get("service_tier").asText());
        assertFalse(unknown.has("service_tier"));
    }

    @Test
    @DisplayName("OAuth Codex 请求按 sub2api 归一化模型、reasoning 和 verbosity")
    void codexOAuthRequestNormalizesModelReasoningAndVerbosity() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .build();
        Method method = OpenAiTransformer.class.getDeclaredMethod(
                "normalizeCodexOAuthRequestBody", String.class, AccountEntity.class);
        method.setAccessible(true);

        JsonNode highAlias = JSON.readTree((String) method.invoke(transformer,
                """
                {
                  "model":" openai/gpt5.4mini ",
                  "reasoning":{"effort":"minimal"},
                  "text":{"verbosity":"low"},
                  "input":[]
                }""", account));
        JsonNode lowModel = JSON.readTree((String) method.invoke(transformer,
                """
                {
                  "model":"gpt-5.2-codex",
                  "text":{"verbosity":"high"},
                  "input":[]
                }""", account));
        JsonNode sparkAlias = JSON.readTree((String) method.invoke(transformer,
                "{\"model\":\"gpt-5.3-codex-spark-xhigh\",\"input\":[]}", account));
        JsonNode removedAlias = JSON.readTree((String) method.invoke(transformer,
                "{\"model\":\"codex-mini-latest\",\"input\":[]}", account));
        JsonNode unknown = JSON.readTree((String) method.invoke(transformer,
                "{\"model\":\"gemini-3-flash-preview\",\"input\":[]}", account));

        assertEquals("gpt-5.4-mini", highAlias.get("model").asText());
        assertEquals("none", highAlias.get("reasoning").get("effort").asText());
        assertEquals("low", highAlias.get("text").get("verbosity").asText());
        assertEquals("gpt-5.2", lowModel.get("model").asText());
        assertFalse(lowModel.get("text").has("verbosity"));
        assertEquals("gpt-5.3-codex-spark", sparkAlias.get("model").asText());
        assertEquals("gpt-5.3-codex", removedAlias.get("model").asText());
        assertEquals("gemini-3-flash-preview", unknown.get("model").asText());
    }

    @Test
    @DisplayName("OAuth Codex 请求删除空 base64 input_image")
    void codexOAuthRequestDropsEmptyBase64InputImages() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .build();
        Method method = OpenAiTransformer.class.getDeclaredMethod(
                "normalizeCodexOAuthRequestBody", String.class, AccountEntity.class);
        method.setAccessible(true);
        String body = """
                {
                  "model":"gpt-5.5",
                  "input":[{"type":"message","role":"user","content":[
                    {"type":"input_image","image_url":"data:image/png;base64, "},
                    {"type":"input_text","text":"Describe this"}
                  ]}]
                }""";

        JsonNode root = JSON.readTree((String) method.invoke(transformer, body, account));

        assertEquals(1, root.get("input").size());
        assertEquals(1, root.get("input").get(0).get("content").size());
        assertEquals("input_text", root.get("input").get(0).get("content").get(0).get("type").asText());
    }

    @Test
    @DisplayName("OAuth Codex 请求兼容旧 functions/function_call 字段")
    void codexOAuthRequestNormalizesLegacyFunctionFields() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .build();
        String body = """
                {
                  "model":"gpt-5.5",
                  "functions":[{"name":"get_weather","description":"Get weather","parameters":{"type":"object"}}],
                  "function_call":{"name":"get_weather"},
                  "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"Hi"}]}]
                }""";

        Method method = OpenAiTransformer.class.getDeclaredMethod(
                "normalizeCodexOAuthRequestBody", String.class, AccountEntity.class);
        method.setAccessible(true);
        JsonNode root = JSON.readTree((String) method.invoke(transformer, body, account));

        assertFalse(root.has("functions"));
        assertFalse(root.has("function_call"));
        assertEquals("function", root.get("tools").get(0).get("type").asText());
        assertEquals("get_weather", root.get("tools").get(0).get("name").asText());
        assertTrue(root.get("tools").get(0).get("parameters").has("properties"));
        assertEquals("function", root.get("tool_choice").get("type").asText());
        assertEquals("get_weather", root.get("tool_choice").get("name").asText());
    }

    @Test
    @DisplayName("OAuth Codex 请求将无效 tool_choice 回退 auto")
    void codexOAuthRequestFallbacksInvalidToolChoiceToAuto() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .build();
        String body = """
                {
                  "model":"gpt-5.5",
                  "tools":[{"type":"function","name":"get_weather","parameters":{"type":"object","properties":{}}}],
                  "tool_choice":{"type":"function","name":"missing_tool"},
                  "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"Hi"}]}]
                }""";

        Method method = OpenAiTransformer.class.getDeclaredMethod(
                "normalizeCodexOAuthRequestBody", String.class, AccountEntity.class);
        method.setAccessible(true);
        JsonNode root = JSON.readTree((String) method.invoke(transformer, body, account));

        assertEquals("auto", root.get("tool_choice").asText());
    }

    @Test
    @DisplayName("OAuth Codex 请求归一化 role=tool 和字符串 input")
    void codexOAuthRequestNormalizesToolRoleAndStringInput() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .build();
        Method method = OpenAiTransformer.class.getDeclaredMethod(
                "normalizeCodexOAuthRequestBody", String.class, AccountEntity.class);
        method.setAccessible(true);

        String toolRoleBody = """
                {
                  "model":"gpt-5.5",
                  "input":[
                    {"role":"tool","tool_call_id":"call_1","content":[{"type":"output_text","text":{"ok":true}}]},
                    {"type":"message","role":"user","content":[{"type":"input_text","text":123}]}
                  ]
                }""";
        JsonNode toolRole = JSON.readTree((String) method.invoke(transformer, toolRoleBody, account));

        assertEquals("function_call_output", toolRole.get("input").get(0).get("type").asText());
        assertEquals("fc1", toolRole.get("input").get(0).get("call_id").asText());
        assertEquals("{\"ok\":true}", toolRole.get("input").get(0).get("output").asText());
        assertEquals("123", toolRole.get("input").get(1).get("content").get(0).get("text").asText());

        String stringInputBody = "{\"model\":\"gpt-5.5\",\"input\":\"Hello\"}";
        JsonNode stringInput = JSON.readTree((String) method.invoke(transformer, stringInputBody, account));

        assertEquals("message", stringInput.get("input").get(0).get("type").asText());
        assertEquals("user", stringInput.get("input").get(0).get("role").asText());
        assertEquals("Hello", stringInput.get("input").get(0).get("content").asText());
    }

    @Test
    @DisplayName("OAuth Codex 请求按 sub2api 过滤 reasoning、普通 id 和 item_reference")
    void codexOAuthRequestFiltersUnsupportedInputReferences() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .build();
        Method method = OpenAiTransformer.class.getDeclaredMethod(
                "normalizeCodexOAuthRequestBody", String.class, AccountEntity.class);
        method.setAccessible(true);

        String withoutContinuation = """
                {
                  "model":"gpt-5.5",
                  "input":[
                    {"type":"reasoning","id":"rs_1","summary":[]},
                    {"type":"message","id":"msg_1","role":"user","content":"Hi","call_id":"call_ignored"}
                  ]
                }""";

        JsonNode noContinuation = JSON.readTree((String) method.invoke(transformer, withoutContinuation, account));
        assertEquals(1, noContinuation.get("input").size());
        JsonNode message = noContinuation.get("input").get(0);
        assertEquals("message", message.get("type").asText());
        assertFalse(message.has("id"));
        assertFalse(message.has("call_id"));

        String withContinuation = """
                {
                  "model":"gpt-5.5",
                  "input":[
                    {"type":"item_reference","id":"call_1"},
                    {"type":"function_call","id":"call_2","arguments":"{}"}
                  ]
                }""";

        JsonNode continuation = JSON.readTree((String) method.invoke(transformer, withContinuation, account));
        assertEquals("fc1", continuation.get("input").get(0).get("id").asText());
        assertEquals("call_2", continuation.get("input").get(1).get("id").asText());
        assertEquals("fc2", continuation.get("input").get(1).get("call_id").asText());
        assertEquals("tool", continuation.get("input").get(1).get("name").asText());
    }

    @Test
    @DisplayName("OAuth Codex 请求转发并隔离会话 Header")
    void codexOAuthRequestForwardsIsolatedSessionHeaders() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .credentials("{\"chatgpt_account_id\":\"chatgpt-acc\"}")
                .build();
        String body = """
                {
                  "model":"gpt-5.5",
                  "prompt_cache_key":"body-cache-key",
                  "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"Hi"}]}],
                  "stream":false
                }""";

        var request = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                "req-1",
                42L,
                body,
                account,
                "token-1",
                new UpstreamRoute(
                        Platform.OPENAI,
                        "responses",
                        "responses",
                        EndpointKind.OPENAI_CODEX_RESPONSES,
                        "https://chatgpt.com/backend-api/codex/responses",
                        false,
                        true,
                        true,
                        "responses",
                        "openai_oauth_codex"),
                null,
                "/backend-api/codex/responses",
                "gpt-5.5",
                true,
                false,
                Map.of(
                        "session_id", "client-session",
                        "conversation_id", "client-conversation",
                        "x-codex-turn-state", "turn-state-1",
                        "User-Agent", "codex-tui/0.139.0",
                        "Accept-Language", "en-US")));

        JsonNode root = JSON.readTree(readBody(request));
        String sessionId = request.headers().firstValue("session_id").orElse("");
        String conversationId = request.headers().firstValue("conversation_id").orElse("");

        assertEquals("body-cache-key", root.get("prompt_cache_key").asText());
        assertEquals("https://chatgpt.com/backend-api/codex/responses", request.uri().toString());
        assertFalse(sessionId.isBlank());
        assertEquals(16, sessionId.length());
        assertNotEquals("client-session", sessionId);
        assertEquals(sessionId, conversationId);
        assertEquals("turn-state-1", request.headers().firstValue("x-codex-turn-state").orElse(""));
        assertEquals("text/event-stream", request.headers().firstValue("Accept").orElse(""));
        assertEquals("responses=experimental", request.headers().firstValue("OpenAI-Beta").orElse(""));
        assertEquals("codex-tui/0.139.0", request.headers().firstValue("User-Agent").orElse(""));
        assertEquals("en-US", request.headers().firstValue("Accept-Language").orElse(""));
        assertEquals("chatgpt-acc", request.headers().firstValue("chatgpt-account-id").orElse(""));
    }

    @Test
    @DisplayName("API Key Responses 请求保留公共状态字段并移除 unsupported 字段")
    void apiKeyResponsesRequestPreservesPublicStateAndRemovesUnsupportedFields() throws Exception {
        OpenAiTransformer transformer = new OpenAiTransformer();
        AccountEntity account = AccountEntity.builder()
                .id(7L)
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .build();
        String body = """
                {
                  "model":"gpt-5.5",
                  "input":"Hi",
                  "service_tier":"fast",
                  "max_output_tokens":512,
                  "max_completion_tokens":256,
                  "include":["message.output_text.logprobs","web_search_call.results"],
                  "previous_response_id":"resp_prev",
                  "truncation":"auto",
                  "prompt":{"id":"pmpt_1"},
                  "background":true,
                  "conversation":{"id":"conv_1"},
                  "context_management":[{"type":"auto"}],
                  "metadata":{"trace_id":"req_1"},
                  "user":"user_1",
                  "safety_identifier":"safe_user_1",
                  "prompt_cache_key":"tenant:thread",
                  "prompt_cache_retention":"24h",
                  "parallel_tool_calls":true,
                  "max_tool_calls":4,
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
                        EndpointKind.OPENAI_RESPONSES,
                        "https://api.openai.com/v1/responses",
                        true,
                        false,
                        false,
                        "responses",
                        "openai_api_key_responses"),
                null,
                "/v1/responses",
                "gpt-5.5",
                false,
                false,
                Map.of()));

        JsonNode root = JSON.readTree(readBody(request));

        assertEquals("priority", root.get("service_tier").asText());
        assertFalse(root.has("max_output_tokens"));
        assertFalse(root.has("max_completion_tokens"));
        assertEquals("web_search_call.results", root.get("include").get(1).asText());
        assertFalse(root.has("previous_response_id"));
        assertEquals("auto", root.get("truncation").asText());
        assertEquals("pmpt_1", root.get("prompt").get("id").asText());
        assertTrue(root.get("background").asBoolean());
        assertEquals("conv_1", root.get("conversation").get("id").asText());
        assertEquals("auto", root.get("context_management").get(0).get("type").asText());
        assertEquals("req_1", root.get("metadata").get("trace_id").asText());
        assertEquals("user_1", root.get("user").asText());
        assertEquals("tenant:thread", root.get("prompt_cache_key").asText());
        assertFalse(root.has("safety_identifier"));
        assertFalse(root.has("prompt_cache_retention"));
        assertTrue(root.get("parallel_tool_calls").asBoolean());
        assertEquals(4, root.get("max_tool_calls").asInt());
        assertTrue(root.get("store").asBoolean());
        assertFalse(root.get("stream").asBoolean());
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
