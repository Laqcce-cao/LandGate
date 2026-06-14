package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenAiCodexProfile 测试")
class OpenAiCodexProfileTest {

    @Test
    @DisplayName("Codex header/profile 常量稳定")
    void codexConstantsAreStable() {
        assertEquals("codex_cli_rs/0.125.0", OpenAiCodexProfile.CLI_USER_AGENT);
        assertEquals("gpt-5.4", OpenAiCodexProfile.DEFAULT_MODEL);
        assertEquals("You are a helpful coding assistant.", OpenAiCodexProfile.DEFAULT_INSTRUCTIONS);
        assertEquals("codex_cli_rs", OpenAiCodexProfile.ORIGINATOR_CODEX_CLI_RS);
        assertEquals("responses=experimental", OpenAiCodexProfile.OPENAI_BETA_RESPONSES_EXPERIMENTAL);
        assertEquals("codex_cli_only", OpenAiCodexProfile.ACCOUNT_EXTRA_CODEX_CLI_ONLY);
        assertEquals("text/event-stream", OpenAiCodexProfile.ACCEPT_EVENT_STREAM);
        assertEquals("application/json", OpenAiCodexProfile.ACCEPT_JSON);
        assertEquals("Bearer token", OpenAiCodexProfile.bearerToken("token"));
        assertEquals("openai-beta", OpenAiCodexProfile.headerKey(OpenAiCodexProfile.HEADER_OPENAI_BETA));
        assertEquals("x-codex-active-limit", OpenAiCodexProfile.HEADER_CODEX_ACTIVE_LIMIT);
        assertEquals("x-codex-primary-used-percent", OpenAiCodexProfile.HEADER_CODEX_PRIMARY_USED_PERCENT);
        assertEquals("x-codex-secondary-reset-at", OpenAiCodexProfile.HEADER_CODEX_SECONDARY_RESET_AT);
        assertEquals("openai_oauth_codex", OpenAiCodexProfile.RATE_LIMIT_SOURCE);
        assertEquals("primary", OpenAiCodexProfile.RATE_LIMIT_SCOPE_PRIMARY);
        assertEquals("secondary", OpenAiCodexProfile.RATE_LIMIT_SCOPE_SECONDARY);
        assertEquals("5h", OpenAiCodexProfile.RATE_LIMIT_LABEL_SHORT_WINDOW);
        assertEquals("7d", OpenAiCodexProfile.RATE_LIMIT_LABEL_LONG_WINDOW);
        assertEquals(360, OpenAiCodexProfile.RATE_LIMIT_SHORT_WINDOW_MAX_MINUTES);
    }

    @Test
    @DisplayName("Codex unsupported request fields 按 sub2api profile 集中维护")
    void codexUnsupportedRequestFieldsAreStable() {
        assertTrue(OpenAiCodexProfile.unsupportedRequestFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_MAX_OUTPUT_TOKENS));
        assertTrue(OpenAiCodexProfile.unsupportedRequestFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_FREQUENCY_PENALTY));
        assertTrue(OpenAiCodexProfile.unsupportedRequestFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_KEY));
        assertTrue(OpenAiCodexProfile.unsupportedRequestFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_STREAM_OPTIONS));
        assertFalse(OpenAiCodexProfile.unsupportedRequestFields()
                .contains(OpenAiResponsesBodyPolicy.FIELD_PREVIOUS_RESPONSE_ID));
    }

    @Test
    @DisplayName("Codex 模型别名按 sub2api 规则归一化")
    void normalizeCodexModelAliases() {
        assertEquals(OpenAiCodexProfile.DEFAULT_MODEL, OpenAiCodexProfile.normalizeModel(" "));
        assertEquals("gpt-5.4-mini", OpenAiCodexProfile.normalizeModel(" openai/gpt5.4mini "));
        assertEquals("gpt-5.3-codex-spark", OpenAiCodexProfile.normalizeModel("gpt-5.3-codex-spark-xhigh"));
        assertEquals("gpt-5.3-codex", OpenAiCodexProfile.normalizeModel("codex-mini-latest"));
        assertEquals("gemini-3-flash-preview", OpenAiCodexProfile.normalizeModel("gemini-3-flash-preview"));
    }

    @Test
    @DisplayName("Codex text verbosity 能力按 sub2api 规则判断")
    void detectsTextVerbosityCapability() {
        assertTrue(OpenAiCodexProfile.supportsTextVerbosity("gpt-5.3-codex"));
        assertFalse(OpenAiCodexProfile.supportsTextVerbosity("gpt-5.2"));
        assertTrue(OpenAiCodexProfile.supportsTextVerbosity("custom-model"));
    }

    @Test
    @DisplayName("Codex CLI User-Agent 按 sub2api 前缀/包含规则识别")
    void detectsCodexCliUserAgent() {
        assertTrue(OpenAiCodexProfile.isCodexCliUserAgent("codex_cli_rs/0.1.0"));
        assertTrue(OpenAiCodexProfile.isCodexCliUserAgent("Mozilla/5.0 codex_cli_rs/0.1.0"));
        assertTrue(OpenAiCodexProfile.isCodexCliUserAgent("codex_vscode/1.0.0"));
        assertTrue(OpenAiCodexProfile.isCodexCliUserAgent("  Codex_CLI_Rs/0.1.0  "));
        assertFalse(OpenAiCodexProfile.isCodexCliUserAgent("curl/8.0"));
        assertFalse(OpenAiCodexProfile.isCodexCliUserAgent(""));
        assertFalse(OpenAiCodexProfile.isCodexCliUserAgent(null));
    }

    @Test
    @DisplayName("Codex 官方客户端 User-Agent 家族按 sub2api 规则识别")
    void detectsCodexOfficialClientUserAgentFamilies() {
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientUserAgent("codex_cli_rs/0.99.0"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientUserAgent("codex_vscode/1.0.0"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientUserAgent("codex_app/0.1.0"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientUserAgent("codex_chatgpt_desktop/1.0.0"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientUserAgent("codex_atlas/1.0.0"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientUserAgent("codex_exec/0.1.0"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientUserAgent("codex_sdk_ts/0.1.0"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientUserAgent("Codex Desktop/1.2.3"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientUserAgent("Mozilla/5.0 codex_app/0.1.0"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientUserAgent("Codex_VSCode/1.2.3"));
        assertFalse(OpenAiCodexProfile.isCodexOfficialClientUserAgent("curl/8.0.1"));
        assertFalse(OpenAiCodexProfile.isCodexOfficialClientUserAgent(""));
        assertFalse(OpenAiCodexProfile.isCodexOfficialClientUserAgent(null));
    }

    @Test
    @DisplayName("Codex 官方客户端 Originator 家族按 sub2api 规则识别")
    void detectsCodexOfficialClientOriginatorFamilies() {
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientOriginator("codex_cli_rs"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientOriginator("codex_vscode"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientOriginator("codex_app"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientOriginator("codex_chatgpt_desktop"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientOriginator("codex_atlas"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientOriginator("codex_exec"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientOriginator("codex_sdk_ts"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientOriginator("Codex Desktop"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClientOriginator("  codex_vscode  "));
        assertFalse(OpenAiCodexProfile.isCodexOfficialClientOriginator("my_client"));
        assertFalse(OpenAiCodexProfile.isCodexOfficialClientOriginator(""));
        assertFalse(OpenAiCodexProfile.isCodexOfficialClientOriginator(null));
    }

    @Test
    @DisplayName("Codex 官方客户端可由 User-Agent 或 Originator 命中")
    void detectsCodexOfficialClientByEitherHeader() {
        assertTrue(OpenAiCodexProfile.isCodexOfficialClient("curl/8.0", "codex_chatgpt_desktop"));
        assertTrue(OpenAiCodexProfile.isCodexOfficialClient("Codex Desktop/1.2.3", ""));
        assertFalse(OpenAiCodexProfile.isCodexOfficialClient("curl/8.0", "my_client"));
    }
}
