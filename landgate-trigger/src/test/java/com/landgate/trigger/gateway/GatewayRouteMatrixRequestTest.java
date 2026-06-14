package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.converter.AnthropicConverter;
import com.landgate.trigger.gateway.converter.ChatCompletionsConverter;
import com.landgate.trigger.gateway.converter.ConverterRegistry;
import com.landgate.trigger.gateway.converter.ProtocolTranslationService;
import com.landgate.trigger.gateway.converter.ResponsesConverter;
import com.landgate.trigger.gateway.route.AnthropicRouteStrategy;
import com.landgate.trigger.gateway.route.OpenAiApiKeyRouteStrategy;
import com.landgate.trigger.gateway.route.OpenAiOAuthCodexRouteStrategy;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.trigger.gateway.route.UpstreamRouteRequest;
import com.landgate.trigger.gateway.route.UpstreamRouteResolver;
import com.landgate.trigger.gateway.session.OpenAiCompatPromptCacheKeyInjector;
import com.landgate.trigger.gateway.transformer.AnthropicTransformer;
import com.landgate.trigger.gateway.transformer.OpenAiTransformer;
import com.landgate.trigger.gateway.transformer.UpstreamRequestContext;
import com.landgate.trigger.gateway.transformer.UpstreamAnthropicBetaRequestNormalizer;
import com.landgate.trigger.gateway.transformer.UpstreamStreamRequestNormalizer;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.AnthropicApiProfile;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import com.landgate.types.gateway.GatewaySensitiveHeaderPolicy;
import com.landgate.types.gateway.OpenAiAnthropicMessagesCompatPolicy;
import com.landgate.types.gateway.OpenAiCodexProfile;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Gateway route matrix request chain tests")
class GatewayRouteMatrixRequestTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final UpstreamRouteResolver resolver = new UpstreamRouteResolver(List.of(
            new OpenAiOAuthCodexRouteStrategy(),
            new OpenAiApiKeyRouteStrategy(),
            new AnthropicRouteStrategy()
    ));
    private final GatewayProtocolPlanner protocolPlanner = new GatewayProtocolPlanner();
    private final ProtocolTranslationService translationService = translationService();

    @Test
    @DisplayName("Messages client + OpenAI OAuth account routes through Responses IR to Codex Responses")
    void messagesClientToOpenAiOAuthCodexResponses() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.OAUTH,
                "{\"chatgpt_account_id\":\"acct_1\"}", "{}", "[\"responses\"]");
        String clientBody = """
                {
                  "model":"claude-sonnet-4-5",
                  "max_tokens":128,
                  "stream":false,
                  "system":"Project rules",
                  "messages":[{"role":"user","content":"Hello"}]
                }""";

        PreparedRequest prepared = prepare(account, Platform.ANTHROPIC, "messages", clientBody, false, null);
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("messages", prepared.route().clientFormat());
        assertEquals("responses", prepared.route().upstreamFormat());
        assertTrue(prepared.upstreamStream());
        assertEquals("https://chatgpt.com/backend-api/codex/responses", prepared.request().uri().toString());
        assertEquals("Bearer selected-token", prepared.header(OpenAiCodexProfile.HEADER_AUTHORIZATION));
        assertEquals(OpenAiCodexProfile.ACCEPT_EVENT_STREAM, prepared.header(OpenAiCodexProfile.HEADER_ACCEPT));
        assertTrue(prepared.request().headers().firstValue(OpenAiCodexProfile.HEADER_OPENAI_BETA).isEmpty());
        assertTrue(prepared.request().headers().firstValue(OpenAiCodexProfile.HEADER_ORIGINATOR).isEmpty());
        assertEquals(OpenAiCodexProfile.CLI_USER_AGENT, prepared.header(OpenAiCodexProfile.HEADER_USER_AGENT));
        assertTrue(prepared.request().headers().firstValue(GatewaySensitiveHeaderPolicy.HEADER_X_API_KEY).isEmpty());
        assertTrue(upstream.get("stream").asBoolean());
        assertFalse(upstream.has("max_output_tokens"));
        assertEquals("Project rules", upstream.get("instructions").asText());
        assertEquals(2, upstream.get("input").size());
        assertEquals("developer", upstream.get("input").get(0).get("role").asText());
        assertTrue(upstream.get("input").get(0).get("content").get(0).get("text").asText()
                .contains(OpenAiAnthropicMessagesCompatPolicy.TODO_GUARD_MARKER));
        assertEquals("user", upstream.get("input").get(1).get("role").asText());
    }

    @Test
    @DisplayName("Messages client fast-mode beta is filtered by default OpenAI fast policy")
    void messagesClientFastModeBetaMapsToOpenAiResponsesServiceTier() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY,
                "{}", "{}", "[\"responses\"]");
        String clientBody = """
                {
                  "model":"claude-sonnet-4-5",
                  "max_tokens":128,
                  "stream":false,
                  "messages":[{"role":"user","content":"Hello"}]
                }""";

        PreparedRequest prepared = prepare(account, Platform.ANTHROPIC, "messages", clientBody, false, null,
                defaultRequestHeaders(Map.of(AnthropicApiProfile.HEADER_ANTHROPIC_BETA,
                        "foo," + AnthropicClaudeCodeProfile.BETA_FAST_MODE)));
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("messages", prepared.route().clientFormat());
        assertEquals("responses", prepared.route().upstreamFormat());
        assertFalse(upstream.has(OpenAiResponsesBodyPolicy.FIELD_SERVICE_TIER));
    }

    @Test
    @DisplayName("Messages client + OpenAI OAuth account preserves Claude tool ids through Codex body normalization")
    void messagesClientToOpenAiOAuthCodexPreservesClaudeToolIds() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.OAUTH,
                "{\"chatgpt_account_id\":\"acct_1\"}", "{}", "[\"responses\"]");
        String clientBody = """
                {
                  "model":"claude-sonnet-4-5",
                  "max_tokens":128,
                  "stream":false,
                  "messages":[
                    {"role":"user","content":"list files"},
                    {"role":"assistant","content":[{"type":"tool_use","id":"toolu_123","name":"Bash","input":{"command":"ls"}}]},
                    {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_123","content":"ok"}]}
                  ],
                  "tools":[{"name":"Bash","description":"run shell","input_schema":{"type":"object","properties":{"command":{"type":"string"}}}}]
                }""";

        PreparedRequest prepared = prepare(account, Platform.ANTHROPIC, "messages", clientBody, false, null);
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("messages", prepared.route().clientFormat());
        assertEquals("responses", prepared.route().upstreamFormat());
        assertEquals("toolu_123", upstream.get("input").get(2).get("call_id").asText());
        assertEquals("toolu_123", upstream.get("input").get(3).get("call_id").asText());
    }

    @Test
    @DisplayName("Chat client + OpenAI API Key chat account stays raw Chat and forces include_usage only for streaming")
    void chatClientToOpenAiApiKeyRawChat() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY,
                "{}", "{}", "[\"chat_completions\"]");
        String clientBody = """
                {
                  "model":"gpt-5.5",
                  "stream":true,
                  "stream_options":{"include_usage":false},
                  "messages":[{"role":"user","content":"Hello"}]
                }""";

        PreparedRequest prepared = prepare(account, Platform.OPENAI, "chat_completions", clientBody, true, null);
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("chat_completions", prepared.route().clientFormat());
        assertEquals("chat_completions", prepared.route().upstreamFormat());
        assertTrue(prepared.upstreamStream());
        assertEquals("https://api.openai.com/v1/chat/completions", prepared.request().uri().toString());
        assertEquals("Bearer selected-token", prepared.header(OpenAiCodexProfile.HEADER_AUTHORIZATION));
        assertEquals(OpenAiCodexProfile.ACCEPT_EVENT_STREAM, prepared.header(OpenAiCodexProfile.HEADER_ACCEPT));
        assertTrue(prepared.request().headers().firstValue(OpenAiCodexProfile.HEADER_OPENAI_BETA).isEmpty());
        assertTrue(upstream.get("stream").asBoolean());
        assertTrue(upstream.get("stream_options").get("include_usage").asBoolean());
    }

    @Test
    @DisplayName("Responses client + Anthropic account converts through Responses IR to Anthropic Messages")
    void responsesClientToAnthropicMessages() throws Exception {
        AccountEntity account = account(Platform.ANTHROPIC, AccountType.API_KEY,
                "{}", "{}", "[\"messages\"]");
        String clientBody = """
                {
                  "model":"gpt-5.4",
                  "max_output_tokens":128,
                  "input":[
                    {"type":"message","role":"developer","content":[{"type":"input_text","text":"Project rules"}]},
                    {"type":"message","role":"user","content":[{"type":"input_text","text":"Hello"}]}
                  ],
                  "stream":false
                }""";

        PreparedRequest prepared = prepare(account, Platform.OPENAI, "responses", clientBody, false, null);
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("responses", prepared.route().clientFormat());
        assertEquals("messages", prepared.route().upstreamFormat());
        assertFalse(prepared.upstreamStream());
        assertEquals("https://api.anthropic.com/v1/messages", prepared.request().uri().toString());
        assertEquals("selected-token", prepared.header(AnthropicApiProfile.HEADER_X_API_KEY));
        assertTrue(prepared.request().headers().firstValue(OpenAiCodexProfile.HEADER_AUTHORIZATION).isEmpty());
        assertEquals(AnthropicApiProfile.ANTHROPIC_VERSION,
                prepared.header(AnthropicApiProfile.HEADER_ANTHROPIC_VERSION));
        assertEquals("interleaved-thinking-2025-05-14",
                prepared.header(AnthropicApiProfile.HEADER_ANTHROPIC_BETA));
        assertTrue(prepared.request().headers().firstValue(GatewaySensitiveHeaderPolicy.HEADER_X_GOOG_API_KEY).isEmpty());
        assertTrue(prepared.request().headers().firstValue(GatewaySensitiveHeaderPolicy.HEADER_COOKIE).isEmpty());
        assertEquals("Project rules", upstream.get("system").asText());
        assertEquals(128, upstream.get("max_tokens").asInt());
        assertEquals("user", upstream.get("messages").get(0).get("role").asText());
    }

    @Test
    @DisplayName("Responses client + OpenAI API Key chat account uses explicit LandGate extension route")
    void responsesClientToOpenAiApiKeyChatExtension() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY,
                "{}", "{}", "[\"chat_completions\"]");
        String clientBody = """
                {
                  "model":"gpt-5.4",
                  "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"Hello"}]}],
                  "stream":false
                }""";

        PreparedRequest prepared = prepare(account, Platform.OPENAI, "responses", clientBody, false, null);
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("responses", prepared.route().clientFormat());
        assertEquals("chat_completions", prepared.route().upstreamFormat());
        assertFalse(prepared.upstreamStream());
        assertEquals("https://api.openai.com/v1/chat/completions", prepared.request().uri().toString());
        assertEquals("Bearer selected-token", prepared.header(OpenAiCodexProfile.HEADER_AUTHORIZATION));
        assertEquals(OpenAiCodexProfile.ACCEPT_JSON, prepared.header(OpenAiCodexProfile.HEADER_ACCEPT));
        assertEquals("user", upstream.get("messages").get(0).get("role").asText());
        assertFalse(upstream.has("stream_options"));
    }

    @Test
    @DisplayName("Messages client + Anthropic account stays native Anthropic Messages")
    void messagesClientToAnthropicMessagesPassthrough() throws Exception {
        AccountEntity account = account(Platform.ANTHROPIC, AccountType.API_KEY,
                "{}", "{}", "[\"messages\"]");
        String clientBody = """
                {
                  "model":"claude-sonnet-4-5",
                  "max_tokens":256,
                  "stream":true,
                  "system":"Project rules",
                  "messages":[{"role":"user","content":"Hello"}]
                }""";

        PreparedRequest prepared = prepare(account, Platform.ANTHROPIC, "messages", clientBody, true, null);
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("messages", prepared.route().clientFormat());
        assertEquals("messages", prepared.route().upstreamFormat());
        assertTrue(prepared.upstreamStream());
        assertEquals("https://api.anthropic.com/v1/messages", prepared.request().uri().toString());
        assertEquals("selected-token", prepared.header(AnthropicApiProfile.HEADER_X_API_KEY));
        assertEquals(AnthropicApiProfile.ANTHROPIC_VERSION,
                prepared.header(AnthropicApiProfile.HEADER_ANTHROPIC_VERSION));
        assertEquals("interleaved-thinking-2025-05-14",
                prepared.header(AnthropicApiProfile.HEADER_ANTHROPIC_BETA));
        assertTrue(prepared.request().headers().firstValue(OpenAiCodexProfile.HEADER_AUTHORIZATION).isEmpty());
        assertEquals("Project rules", upstream.get("system").asText());
        assertEquals(256, upstream.get("max_tokens").asInt());
        assertTrue(upstream.get("stream").asBoolean());
    }

    @Test
    @DisplayName("Chat client + Anthropic account converts Chat through Responses IR to Anthropic Messages")
    void chatClientToAnthropicMessages() throws Exception {
        AccountEntity account = account(Platform.ANTHROPIC, AccountType.API_KEY,
                "{}", "{}", "[\"messages\"]");
        String clientBody = """
                {
                  "model":"gpt-5.4",
                  "max_completion_tokens":256,
                  "stream":true,
                  "messages":[
                    {"role":"system","content":"Project rules"},
                    {"role":"user","content":"Hello"}
                  ]
                }""";

        PreparedRequest prepared = prepare(account, Platform.OPENAI, "chat_completions", clientBody, true, null);
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("chat_completions", prepared.route().clientFormat());
        assertEquals("messages", prepared.route().upstreamFormat());
        assertTrue(prepared.upstreamStream());
        assertEquals("https://api.anthropic.com/v1/messages", prepared.request().uri().toString());
        assertEquals("selected-token", prepared.header(AnthropicApiProfile.HEADER_X_API_KEY));
        assertEquals("interleaved-thinking-2025-05-14",
                prepared.header(AnthropicApiProfile.HEADER_ANTHROPIC_BETA));
        assertTrue(prepared.request().headers().firstValue(OpenAiCodexProfile.HEADER_AUTHORIZATION).isEmpty());
        assertEquals("Project rules", upstream.get("system").asText());
        assertEquals(256, upstream.get("max_tokens").asInt());
        assertEquals("user", upstream.get("messages").get(0).get("role").asText());
        assertTrue(upstream.get("stream").asBoolean());
    }

    @Test
    @DisplayName("Responses client + OpenAI OAuth account stays Responses and uses Codex headers")
    void responsesClientToOpenAiOAuthCodexResponses() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.OAUTH,
                "{\"chatgpt_account_id\":\"acct_1\"}", "{}", "[\"responses\"]");
        String clientBody = """
                {
                  "model":"gpt-5.4",
                  "stream":false,
                  "service_tier":"fast",
                  "input":[
                    {"role":"developer","content":"Project rules"},
                    {"role":"user","content":"Hello"}
                  ]
                }""";

        PreparedRequest prepared = prepare(account, Platform.OPENAI, "responses", clientBody, false, null);
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("responses", prepared.route().clientFormat());
        assertEquals("responses", prepared.route().upstreamFormat());
        assertTrue(prepared.upstreamStream());
        assertEquals("https://chatgpt.com/backend-api/codex/responses", prepared.request().uri().toString());
        assertEquals(OpenAiCodexProfile.OPENAI_BETA_RESPONSES_EXPERIMENTAL,
                prepared.header(OpenAiCodexProfile.HEADER_OPENAI_BETA));
        assertEquals(OpenAiCodexProfile.ORIGINATOR_CODEX_CLI_RS,
                prepared.header(OpenAiCodexProfile.HEADER_ORIGINATOR));
        assertTrue(upstream.get("stream").asBoolean());
        assertFalse(upstream.has("service_tier"));
        assertEquals("Project rules", upstream.get("instructions").asText());
        assertEquals("user", upstream.get("input").get(0).get("role").asText());
    }

    @Test
    @DisplayName("Chat client + OpenAI OAuth account converts Chat to Codex Responses")
    void chatClientToOpenAiOAuthCodexResponses() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.OAUTH,
                "{\"chatgpt_account_id\":\"acct_1\"}", "{}", "[\"responses\"]");
        String clientBody = """
                {
                  "model":"gpt-5.4",
                  "stream":false,
                  "messages":[
                    {"role":"system","content":"Project rules"},
                    {"role":"user","content":"Hello"}
                  ]
                }""";

        PreparedRequest prepared = prepare(account, Platform.OPENAI, "chat_completions", clientBody, false, null);
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("chat_completions", prepared.route().clientFormat());
        assertEquals("responses", prepared.route().upstreamFormat());
        assertTrue(prepared.upstreamStream());
        assertEquals("https://chatgpt.com/backend-api/codex/responses", prepared.request().uri().toString());
        assertEquals(OpenAiCodexProfile.ACCEPT_EVENT_STREAM, prepared.header(OpenAiCodexProfile.HEADER_ACCEPT));
        assertEquals(OpenAiCodexProfile.OPENAI_BETA_RESPONSES_EXPERIMENTAL,
                prepared.header(OpenAiCodexProfile.HEADER_OPENAI_BETA));
        assertTrue(upstream.get("stream").asBoolean());
        assertTrue(upstream.get("prompt_cache_key").asText().startsWith("compat_cc_"));
        assertFalse(prepared.header(OpenAiCodexProfile.HEADER_SESSION_ID).isBlank());
        assertEquals("Project rules", upstream.get("instructions").asText());
        assertEquals("user", upstream.get("input").get(0).get("role").asText());
    }

    @Test
    @DisplayName("Chat client + OpenAI OAuth account derives stable compat prompt_cache_key from first turn")
    void chatClientToOpenAiOAuthCodexDerivesStablePromptCacheKey() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.OAUTH,
                "{\"chatgpt_account_id\":\"acct_1\"}", "{}", "[\"responses\"]");
        String firstTurn = """
                {
                  "model":"gpt-5.4",
                  "stream":false,
                  "tools":[{"type":"function","function":{"name":"get_weather"}}],
                  "messages":[
                    {"role":"system","content":"Project rules"},
                    {"role":"user","content":"Hello"}
                  ]
                }""";
        String laterTurn = """
                {
                  "model":"gpt-5.4",
                  "stream":false,
                  "tools":[{"type":"function","function":{"name":"get_weather"}}],
                  "messages":[
                    {"role":"system","content":"Project rules"},
                    {"role":"user","content":"Hello"},
                    {"role":"assistant","content":"Hi"},
                    {"role":"user","content":"Next"}
                  ]
                }""";

        PreparedRequest first = prepare(account, Platform.OPENAI, "chat_completions", firstTurn, false, null);
        PreparedRequest later = prepare(account, Platform.OPENAI, "chat_completions", laterTurn, false, null);
        JsonNode firstBody = JSON.readTree(first.body());
        JsonNode laterBody = JSON.readTree(later.body());

        assertEquals(firstBody.get("prompt_cache_key").asText(), laterBody.get("prompt_cache_key").asText());
        assertEquals(first.header(OpenAiCodexProfile.HEADER_SESSION_ID),
                later.header(OpenAiCodexProfile.HEADER_SESSION_ID));
    }

    @Test
    @DisplayName("Chat client + OpenAI OAuth account does not auto prompt_cache_key for non-Codex-compatible model")
    void chatClientToOpenAiOAuthCodexSkipsPromptCacheKeyForNonCompatModel() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.OAUTH,
                "{\"chatgpt_account_id\":\"acct_1\"}", "{}", "[\"responses\"]");
        String clientBody = """
                {
                  "model":"gpt-4o",
                  "stream":false,
                  "messages":[{"role":"user","content":"Hello"}]
                }""";

        PreparedRequest prepared = prepare(account, Platform.OPENAI, "chat_completions", clientBody, false, null,
                "gpt-4o", defaultRequestHeaders(Map.of()));
        JsonNode upstream = JSON.readTree(prepared.body());

        assertFalse(upstream.has("prompt_cache_key"));
        assertTrue(prepared.header(OpenAiCodexProfile.HEADER_SESSION_ID).isBlank());
    }

    @Test
    @DisplayName("Messages client + OpenAI API Key responses account converts to public Responses")
    void messagesClientToOpenAiApiKeyResponses() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY,
                "{}", "{}", "[\"responses\"]");
        String clientBody = """
                {
                  "model":"claude-sonnet-4-5",
                  "max_tokens":128,
                  "stream":false,
                  "system":"Project rules",
                  "messages":[{"role":"user","content":"Hello"}]
                }""";

        PreparedRequest prepared = prepare(account, Platform.ANTHROPIC, "messages", clientBody, false, null);
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("messages", prepared.route().clientFormat());
        assertEquals("responses", prepared.route().upstreamFormat());
        assertTrue(prepared.upstreamStream());
        assertEquals("https://api.openai.com/v1/responses", prepared.request().uri().toString());
        assertEquals("Bearer selected-token", prepared.header(OpenAiCodexProfile.HEADER_AUTHORIZATION));
        assertEquals(OpenAiCodexProfile.ACCEPT_EVENT_STREAM, prepared.header(OpenAiCodexProfile.HEADER_ACCEPT));
        assertTrue(prepared.request().headers().firstValue(OpenAiCodexProfile.HEADER_OPENAI_BETA).isEmpty());
        assertFalse(upstream.has("max_output_tokens"));
        assertTrue(upstream.get("stream").asBoolean());
        assertEquals("developer", upstream.get("input").get(0).get("role").asText());
        assertEquals("user", upstream.get("input").get(1).get("role").asText());
    }

    @Test
    @DisplayName("Responses client + OpenAI API Key responses account stays public Responses")
    void responsesClientToOpenAiApiKeyResponsesPassthrough() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY,
                "{}", "{}", "[\"responses\"]");
        String clientBody = """
                {
                  "model":"gpt-5.4",
                  "service_tier":"fast",
                  "stream":false,
                  "input":[{"role":"user","content":"Hello"}]
                }""";

        PreparedRequest prepared = prepare(account, Platform.OPENAI, "responses", clientBody, false, null);
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("responses", prepared.route().clientFormat());
        assertEquals("responses", prepared.route().upstreamFormat());
        assertFalse(prepared.upstreamStream());
        assertEquals("https://api.openai.com/v1/responses", prepared.request().uri().toString());
        assertEquals("Bearer selected-token", prepared.header(OpenAiCodexProfile.HEADER_AUTHORIZATION));
        assertEquals(OpenAiCodexProfile.ACCEPT_JSON, prepared.header(OpenAiCodexProfile.HEADER_ACCEPT));
        assertFalse(upstream.has("service_tier"));
        assertFalse(upstream.get("stream").asBoolean());
        assertEquals("user", upstream.get("input").get(0).get("role").asText());
    }

    @Test
    @DisplayName("Responses compact + OpenAI API Key responses applies compact account mapping")
    void responsesCompactToOpenAiApiKeyResponsesAppliesCompactMapping() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY,
                "{\"compact_model_mapping\":{\"gpt-5.4\":\"gpt-5.4-openai-compact\"}}",
                "{\"openai_compact_supported\":true}", "[\"responses\"]");
        String clientBody = """
                {
                  "model":"gpt-5.4",
                  "input":"Compact me",
                  "stream":true,
                  "store":true,
                  "prompt_cache_key":"tenant:thread"
                }""";

        PreparedRequest prepared = prepare(account, Platform.OPENAI, "responses", clientBody,
                true, "/v1/responses/compact");
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("https://api.openai.com/v1/responses/compact", prepared.request().uri().toString());
        assertFalse(prepared.upstreamStream());
        assertEquals(OpenAiCodexProfile.ACCEPT_JSON, prepared.header(OpenAiCodexProfile.HEADER_ACCEPT));
        assertEquals("gpt-5.4-openai-compact", upstream.get("model").asText());
        assertFalse(upstream.has("stream"));
        assertFalse(upstream.has("store"));
        assertFalse(upstream.has("prompt_cache_key"));
    }

    @Test
    @DisplayName("Responses compact + OpenAI OAuth Codex keeps compact mapping ahead of Codex model normalization")
    void responsesCompactToOpenAiOAuthCodexKeepsCompactMapping() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.OAUTH,
                "{\"chatgpt_account_id\":\"acct_1\",\"compact_model_mapping\":{\"gpt-5.4\":\"gpt-5.4-openai-compact\"}}",
                "{\"openai_compact_supported\":true}", "[\"responses\"]");
        String clientBody = """
                {
                  "model":"gpt-5.4",
                  "input":"Compact me",
                  "stream":true,
                  "store":true,
                  "prompt_cache_key":"tenant:thread"
                }""";

        PreparedRequest prepared = prepare(account, Platform.OPENAI, "responses", clientBody,
                true, "/v1/responses/compact");
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("https://chatgpt.com/backend-api/codex/responses/compact", prepared.request().uri().toString());
        assertFalse(prepared.upstreamStream());
        assertEquals(OpenAiCodexProfile.ACCEPT_JSON, prepared.header(OpenAiCodexProfile.HEADER_ACCEPT));
        assertEquals("gpt-5.4-openai-compact", upstream.get("model").asText());
        assertFalse(upstream.has("stream"));
        assertFalse(upstream.has("store"));
        assertFalse(upstream.has("prompt_cache_key"));
    }

    @Test
    @DisplayName("Chat client + OpenAI API Key responses account converts Chat to public Responses")
    void chatClientToOpenAiApiKeyResponses() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY,
                "{}", "{}", "[\"responses\"]");
        String clientBody = """
                {
                  "model":"gpt-5.4",
                  "stream":true,
                  "messages":[
                    {"role":"system","content":"Project rules"},
                    {"role":"user","content":"Hello"}
                  ]
                }""";

        PreparedRequest prepared = prepare(account, Platform.OPENAI, "chat_completions", clientBody, true, null);
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("chat_completions", prepared.route().clientFormat());
        assertEquals("responses", prepared.route().upstreamFormat());
        assertTrue(prepared.upstreamStream());
        assertEquals("https://api.openai.com/v1/responses", prepared.request().uri().toString());
        assertEquals("Bearer selected-token", prepared.header(OpenAiCodexProfile.HEADER_AUTHORIZATION));
        assertEquals(OpenAiCodexProfile.ACCEPT_EVENT_STREAM, prepared.header(OpenAiCodexProfile.HEADER_ACCEPT));
        assertTrue(upstream.get("stream").asBoolean());
        assertEquals("system", upstream.get("input").get(0).get("role").asText());
        assertEquals("user", upstream.get("input").get(1).get("role").asText());
    }

    @Test
    @DisplayName("Chat client + Responses-shape body preserves input when routed to OpenAI API Key Responses")
    void chatClientResponsesShapeBodyToOpenAiApiKeyResponses() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY,
                "{}", "{}", "[\"responses\"]");
        String clientBody = """
                {
                  "model":"gpt-5.4",
                  "stream":false,
                  "service_tier":"fast",
                  "max_output_tokens":512,
                  "prompt_cache_key":"tenant:thread",
                  "prompt_cache_retention":"24h",
                  "safety_identifier":"safe-user",
                  "metadata":{"trace_id":"req_1"},
                  "stream_options":{"include_usage":true},
                  "input":[{"type":"message","role":"user","content":"Hello from Responses shape"}]
                }""";

        PreparedRequest prepared = prepare(account, Platform.OPENAI, "chat_completions", clientBody, false, null);
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("chat_completions", prepared.route().clientFormat());
        assertEquals("responses", prepared.route().upstreamFormat());
        assertTrue(prepared.upstreamStream());
        assertEquals("https://api.openai.com/v1/responses", prepared.request().uri().toString());
        assertTrue(upstream.get("stream").asBoolean());
        assertFalse(upstream.has("service_tier"));
        assertEquals("tenant:thread", upstream.get("prompt_cache_key").asText());
        assertFalse(upstream.has("max_output_tokens"));
        assertFalse(upstream.has("prompt_cache_retention"));
        assertFalse(upstream.has("safety_identifier"));
        assertFalse(upstream.has("metadata"));
        assertFalse(upstream.has("stream_options"));
        assertEquals("Hello from Responses shape", upstream.get("input").get(0).get("content").asText());
    }

    @Test
    @DisplayName("Chat client + Responses-shape body preserves input when routed to OpenAI OAuth Codex")
    void chatClientResponsesShapeBodyToOpenAiOAuthCodex() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.OAUTH,
                "{\"chatgpt_account_id\":\"acct_1\"}", "{}", "[\"responses\"]");
        String clientBody = """
                {
                  "model":"gpt-5.4",
                  "stream":false,
                  "service_tier":"fast",
                  "prompt_cache_key":"tenant:thread",
                  "prompt_cache_retention":"24h",
                  "safety_identifier":"safe-user",
                  "metadata":{"trace_id":"req_1"},
                  "stream_options":{"include_usage":true},
                  "input":[{"type":"message","role":"user","content":"Hello from Responses shape"}]
                }""";

        PreparedRequest prepared = prepare(account, Platform.OPENAI, "chat_completions", clientBody, false, null);
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("chat_completions", prepared.route().clientFormat());
        assertEquals("responses", prepared.route().upstreamFormat());
        assertTrue(prepared.upstreamStream());
        assertEquals("https://chatgpt.com/backend-api/codex/responses", prepared.request().uri().toString());
        assertEquals(OpenAiCodexProfile.ACCEPT_EVENT_STREAM, prepared.header(OpenAiCodexProfile.HEADER_ACCEPT));
        assertTrue(upstream.get("stream").asBoolean());
        assertFalse(upstream.has("service_tier"));
        assertEquals("tenant:thread", upstream.get("prompt_cache_key").asText());
        assertFalse(upstream.has("prompt_cache_retention"));
        assertFalse(upstream.has("safety_identifier"));
        assertFalse(upstream.has("metadata"));
        assertFalse(upstream.has("stream_options"));
        assertEquals("Hello from Responses shape", upstream.get("input").get(0).get("content").asText());
    }

    @Test
    @DisplayName("Chat client + Responses-shape OAuth Codex derives Sub2API compat prompt_cache_key from model")
    void chatClientResponsesShapeOAuthCodexDerivesModelOnlyCompatPromptCacheKey() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.OAUTH,
                "{\"chatgpt_account_id\":\"acct_1\"}", "{}", "[\"responses\"]");
        String firstBody = """
                {
                  "model":"gpt-5.4",
                  "stream":false,
                  "input":[{"type":"message","role":"user","content":"First Responses-shaped request"}]
                }""";
        String laterBody = """
                {
                  "model":"gpt-5.4",
                  "stream":false,
                  "input":[{"type":"message","role":"user","content":"Different Responses-shaped request"}]
                }""";

        PreparedRequest first = prepare(account, Platform.OPENAI, "chat_completions", firstBody, false, null);
        PreparedRequest later = prepare(account, Platform.OPENAI, "chat_completions", laterBody, false, null);
        JsonNode firstUpstream = JSON.readTree(first.body());
        JsonNode laterUpstream = JSON.readTree(later.body());

        assertTrue(firstUpstream.get("prompt_cache_key").asText().startsWith("compat_cc_"));
        assertEquals(firstUpstream.get("prompt_cache_key").asText(),
                laterUpstream.get("prompt_cache_key").asText());
        assertEquals(first.header(OpenAiCodexProfile.HEADER_SESSION_ID),
                later.header(OpenAiCodexProfile.HEADER_SESSION_ID));
        assertEquals("First Responses-shaped request", firstUpstream.get("input").get(0).get("content").asText());
        assertEquals("Different Responses-shaped request", laterUpstream.get("input").get(0).get("content").asText());
    }

    @Test
    @DisplayName("Messages client + OpenAI API Key chat account converts through Responses IR to Chat")
    void messagesClientToOpenAiApiKeyChatExtension() throws Exception {
        AccountEntity account = account(Platform.OPENAI, AccountType.API_KEY,
                "{}", "{}", "[\"chat_completions\"]");
        String clientBody = """
                {
                  "model":"claude-sonnet-4-5",
                  "max_tokens":128,
                  "stream":false,
                  "system":"Project rules",
                  "messages":[{"role":"user","content":"Hello"}]
                }""";

        PreparedRequest prepared = prepare(account, Platform.ANTHROPIC, "messages", clientBody, false, null);
        JsonNode upstream = JSON.readTree(prepared.body());

        assertEquals("messages", prepared.route().clientFormat());
        assertEquals("chat_completions", prepared.route().upstreamFormat());
        assertFalse(prepared.upstreamStream());
        assertEquals("https://api.openai.com/v1/chat/completions", prepared.request().uri().toString());
        assertEquals("Bearer selected-token", prepared.header(OpenAiCodexProfile.HEADER_AUTHORIZATION));
        assertEquals(OpenAiCodexProfile.ACCEPT_JSON, prepared.header(OpenAiCodexProfile.HEADER_ACCEPT));
        assertFalse(upstream.get("stream").asBoolean());
        assertEquals("developer", upstream.get("messages").get(0).get("role").asText());
        assertEquals("user", upstream.get("messages").get(1).get("role").asText());
        assertFalse(upstream.has("stream_options"));
    }

    private PreparedRequest prepare(AccountEntity account,
                                    Platform requestPlatform,
                                    String requestFormat,
                                    String clientBody,
                                    boolean clientStream,
                                    String upstreamPath) throws Exception {
        return prepare(account, requestPlatform, requestFormat, clientBody, clientStream, upstreamPath,
                defaultRequestHeaders(Map.of()));
    }

    private PreparedRequest prepare(AccountEntity account,
                                    Platform requestPlatform,
                                    String requestFormat,
                                    String clientBody,
                                    boolean clientStream,
                                    String upstreamPath,
                                    String requestedModel,
                                    Map<String, String> requestHeaders) throws Exception {
        return prepareInternal(account, requestPlatform, requestFormat, clientBody, clientStream, upstreamPath,
                requestedModel, requestHeaders);
    }

    private PreparedRequest prepare(AccountEntity account,
                                    Platform requestPlatform,
                                    String requestFormat,
                                    String clientBody,
                                    boolean clientStream,
                                    String upstreamPath,
                                    Map<String, String> requestHeaders) throws Exception {
        return prepareInternal(account, requestPlatform, requestFormat, clientBody, clientStream, upstreamPath,
                "gpt-5.5", requestHeaders);
    }

    private PreparedRequest prepareInternal(AccountEntity account,
                                            Platform requestPlatform,
                                            String requestFormat,
                                            String clientBody,
                                            boolean clientStream,
                                            String upstreamPath,
                                            String requestedModel,
                                            Map<String, String> requestHeaders) throws Exception {
        UpstreamRoute route = resolver.resolve(UpstreamRouteRequest.builder()
                .account(account)
                .requestPlatform(requestPlatform)
                .requestFormat(requestFormat)
                .upstreamPath(upstreamPath)
                .requestedModel(requestedModel)
                .build());
        boolean upstreamStream = route.forceNonStreamingResponse() ? false : route.forceStreaming() || clientStream;
        GatewayProtocolPlan plan = protocolPlanner.plan(requestPlatform, route);
        String upstreamBody = plan.prepareRequestBody("req_matrix", clientBody, translationService::translateRequest);
        upstreamBody = UpstreamAnthropicBetaRequestNormalizer.normalize(upstreamBody, route, requestHeaders);
        upstreamBody = OpenAiCompatPromptCacheKeyInjector.injectChatCompletionsCodexCompat(
                account, route, clientBody, upstreamBody, requestedModel).body();
        upstreamBody = UpstreamStreamRequestNormalizer.normalize(upstreamBody, route, upstreamStream);

        HttpRequest request = transformerFor(account).buildUpstreamRequest(new UpstreamRequestContext(
                "req_matrix",
                99L,
                upstreamBody,
                account,
                "selected-token",
                route,
                null,
                upstreamPath,
                requestedModel,
                upstreamStream,
                false,
                requestHeaders));

        return new PreparedRequest(route, upstreamStream, request, readBody(request));
    }

    private static Map<String, String> defaultRequestHeaders(Map<String, String> overrides) {
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put(GatewaySensitiveHeaderPolicy.HEADER_AUTHORIZATION, "Bearer inbound");
        headers.put(GatewaySensitiveHeaderPolicy.HEADER_X_API_KEY, "inbound-api-key");
        headers.put("X-Goog-Api-Key", "inbound-goog-key");
        headers.put(GatewaySensitiveHeaderPolicy.HEADER_COOKIE, "secret=1");
        headers.put(GatewaySensitiveHeaderPolicy.HEADER_PROXY_AUTHORIZATION, "Basic inbound-proxy");
        headers.put(AnthropicApiProfile.HEADER_ANTHROPIC_BETA, "interleaved-thinking-2025-05-14");
        headers.put("User-Agent", "curl/8.0");
        headers.put("Accept-Language", "zh-CN");
        headers.put("OpenAI-Beta", "responses=experimental");
        headers.put("x-codex-turn-state", "turn_state");
        headers.putAll(overrides);
        return Map.copyOf(headers);
    }

    private static com.landgate.trigger.gateway.transformer.IRequestTransformer transformerFor(AccountEntity account) {
        if (account.getPlatform() == Platform.ANTHROPIC) {
            return new AnthropicTransformer(null, null, null, null);
        }
        return new OpenAiTransformer();
    }

    private static AccountEntity account(Platform platform,
                                         AccountType type,
                                         String credentials,
                                         String extra,
                                         String supportedProtocols) {
        return AccountEntity.builder()
                .id(1L)
                .name(platform.name().toLowerCase() + "-" + type.name().toLowerCase())
                .platform(platform)
                .type(type)
                .credentials(credentials)
                .extra(extra)
                .supportedProtocols(supportedProtocols)
                .build();
    }

    private static ProtocolTranslationService translationService() {
        ConverterRegistry registry = new ConverterRegistry();
        registry.register(List.of(
                new AnthropicConverter(),
                new ResponsesConverter(),
                new ChatCompletionsConverter()));
        return new ProtocolTranslationService(registry);
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

    private record PreparedRequest(
            UpstreamRoute route,
            boolean upstreamStream,
            HttpRequest request,
            String body
    ) {
        String header(String name) {
            return request.headers().firstValue(name).orElse("");
        }
    }
}
