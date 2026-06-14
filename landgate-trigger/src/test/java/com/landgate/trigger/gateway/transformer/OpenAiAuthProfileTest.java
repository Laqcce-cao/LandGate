package com.landgate.trigger.gateway.transformer;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.GatewaySensitiveHeaderPolicy;
import com.landgate.types.gateway.OpenAiCodexProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAiAuthProfile 测试")
class OpenAiAuthProfileTest {

    @Test
    @DisplayName("OpenAI API Key Responses 只使用 selected account token 并过滤 Codex/入站凭证 Header")
    void apiKeyResponsesUsesSelectedTokenAndPublicAllowlist() {
        Map<String, String> headers = headersFor(
                AccountType.API_KEY,
                "",
                route(EndpointKind.OPENAI_RESPONSES, "responses", "responses", false),
                false,
                "{\"model\":\"gpt-5.5\",\"input\":\"Hi\"}",
                Map.ofEntries(
                        Map.entry("User-Agent", "curl/8.0"),
                        Map.entry("Accept-Language", "zh-CN"),
                        Map.entry(GatewaySensitiveHeaderPolicy.HEADER_AUTHORIZATION, "Bearer inbound"),
                        Map.entry(GatewaySensitiveHeaderPolicy.HEADER_X_API_KEY, "inbound-api-key"),
                        Map.entry("OpenAI-Beta", "responses=experimental"),
                        Map.entry("session_id", "sess_1"),
                        Map.entry("x-codex-turn-state", "turn_state")));

        assertEquals("Bearer selected-token", headers.get(OpenAiCodexProfile.HEADER_AUTHORIZATION));
        assertEquals(OpenAiCodexProfile.CONTENT_TYPE_JSON, headers.get(OpenAiCodexProfile.HEADER_CONTENT_TYPE));
        assertEquals(OpenAiCodexProfile.ACCEPT_JSON, headers.get(OpenAiCodexProfile.HEADER_ACCEPT));
        assertEquals("curl/8.0", headers.get(OpenAiCodexProfile.HEADER_USER_AGENT));
        assertEquals("zh-CN", headers.get(OpenAiCodexProfile.HEADER_ACCEPT_LANGUAGE));
        assertFalse(headers.containsKey(GatewaySensitiveHeaderPolicy.HEADER_X_API_KEY));
        assertFalse(headers.containsKey(OpenAiCodexProfile.HEADER_OPENAI_BETA));
        assertFalse(headers.containsKey(OpenAiCodexProfile.HEADER_SESSION_ID));
        assertFalse(headers.containsKey(OpenAiCodexProfile.HEADER_X_CODEX_TURN_STATE));
    }

    @Test
    @DisplayName("OpenAI API Key raw Chat 流式请求按 sub2api 使用 event-stream 且只透传通用 Header")
    void rawChatStreamingUsesEventStreamAndPublicAllowlist() {
        Map<String, String> headers = headersFor(
                AccountType.API_KEY,
                "",
                route(EndpointKind.OPENAI_CHAT_COMPLETIONS, "chat_completions", "chat_completions", false),
                true,
                "{\"model\":\"gpt-5.5\",\"stream\":true,\"messages\":[]}",
                Map.of(
                        "Accept", "application/json",
                        "User-Agent", "curl/8.0",
                        "Accept-Language", "en-US",
                        "OpenAI-Beta", "responses=experimental"));

        assertEquals("Bearer selected-token", headers.get(OpenAiCodexProfile.HEADER_AUTHORIZATION));
        assertEquals(OpenAiCodexProfile.ACCEPT_EVENT_STREAM, headers.get(OpenAiCodexProfile.HEADER_ACCEPT));
        assertEquals("curl/8.0", headers.get(OpenAiCodexProfile.HEADER_USER_AGENT));
        assertEquals("en-US", headers.get(OpenAiCodexProfile.HEADER_ACCEPT_LANGUAGE));
        assertFalse(headers.containsKey(OpenAiCodexProfile.HEADER_OPENAI_BETA));
    }

    @Test
    @DisplayName("OpenAI OAuth Codex 使用 Codex header profile 且不泄漏入站 LandGate 凭证")
    void oauthCodexUsesCodexProfileWithoutInboundCredentialLeak() {
        Map<String, String> headers = headersFor(
                AccountType.OAUTH,
                "{\"chatgpt_account_id\":\"acct_1\"}",
                route(EndpointKind.OPENAI_CODEX_RESPONSES, "responses", "responses", true),
                true,
                "{\"model\":\"gpt-5.5\",\"stream\":true,\"prompt_cache_key\":\"tenant:thread\"}",
                Map.ofEntries(
                        Map.entry("User-Agent", "curl/8.0"),
                        Map.entry(GatewaySensitiveHeaderPolicy.HEADER_AUTHORIZATION, "Bearer inbound"),
                        Map.entry(GatewaySensitiveHeaderPolicy.HEADER_X_API_KEY, "inbound-api-key"),
                        Map.entry("session_id", "sess_1"),
                        Map.entry("conversation_id", "conv_1"),
                        Map.entry("x-codex-turn-state", "turn_state")));

        assertEquals("Bearer selected-token", headers.get(OpenAiCodexProfile.HEADER_AUTHORIZATION));
        assertEquals("acct_1", headers.get(OpenAiCodexProfile.HEADER_CHATGPT_ACCOUNT_ID));
        assertEquals(OpenAiCodexProfile.ACCEPT_EVENT_STREAM, headers.get(OpenAiCodexProfile.HEADER_ACCEPT));
        assertEquals(OpenAiCodexProfile.OPENAI_BETA_RESPONSES_EXPERIMENTAL,
                headers.get(OpenAiCodexProfile.HEADER_OPENAI_BETA));
        assertEquals(OpenAiCodexProfile.ORIGINATOR_CODEX_CLI_RS,
                headers.get(OpenAiCodexProfile.HEADER_ORIGINATOR));
        assertEquals(OpenAiCodexProfile.CLI_USER_AGENT, headers.get(OpenAiCodexProfile.HEADER_USER_AGENT));
        assertEquals("turn_state", headers.get(OpenAiCodexProfile.HEADER_X_CODEX_TURN_STATE));
        assertNotEquals("sess_1", headers.get(OpenAiCodexProfile.HEADER_SESSION_ID));
        assertNotEquals("conv_1", headers.get(OpenAiCodexProfile.HEADER_CONVERSATION_ID));
        assertFalse(headers.containsKey(GatewaySensitiveHeaderPolicy.HEADER_X_API_KEY));
    }

    private static Map<String, String> headersFor(AccountType accountType,
                                                  String credentials,
                                                  UpstreamRoute route,
                                                  boolean stream,
                                                  String body,
                                                  Map<String, String> requestHeaders) {
        AccountEntity account = AccountEntity.builder()
                .id(1L)
                .platform(Platform.OPENAI)
                .type(accountType)
                .credentials(credentials)
                .build();
        UpstreamHeaders headers = OpenAiAuthProfile.build(new UpstreamRequestContext(
                "req_1",
                99L,
                body,
                account,
                "selected-token",
                route,
                null,
                null,
                null,
                stream,
                false,
                requestHeaders
        ), body);
        return toMap(headers);
    }

    private static UpstreamRoute route(EndpointKind endpointKind,
                                       String clientFormat,
                                       String upstreamFormat,
                                       boolean normalizeCodexBody) {
        return new UpstreamRoute(
                Platform.OPENAI,
                clientFormat,
                upstreamFormat,
                endpointKind,
                "https://upstream.example.com",
                false,
                normalizeCodexBody,
                upstreamFormat,
                "test_route");
    }

    private static Map<String, String> toMap(UpstreamHeaders headers) {
        String[] pairs = headers.toArray();
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }
}
