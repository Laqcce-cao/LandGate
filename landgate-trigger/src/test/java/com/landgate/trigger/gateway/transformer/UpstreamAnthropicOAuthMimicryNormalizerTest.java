package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.client.ClientProfile;
import com.landgate.trigger.gateway.forwarding.AnthropicForwardingRuntimePolicyProvider;
import com.landgate.trigger.gateway.oauth.FingerprintService;
import com.landgate.trigger.gateway.oauth.OAuthMimicryService;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.AnthropicApiProfile;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("UpstreamAnthropicOAuthMimicryNormalizer tests")
class UpstreamAnthropicOAuthMimicryNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("default policy applies translated Anthropic Messages Claude Code body mimicry")
    void defaultPolicyAppliesTranslatedAnthropicMessagesMimicry() throws Exception {
        UpstreamAnthropicOAuthMimicryNormalizer normalizer = new UpstreamAnthropicOAuthMimicryNormalizer(
                new OAuthMimicryService(),
                new FingerprintService(),
                new AnthropicForwardingRuntimePolicyProvider(true, false, false, ""));
        String body = """
                {
                  "model":"claude-sonnet-4-5",
                  "system":"Project instructions",
                  "messages":[{"role":"user","content":"Hello"}],
                  "tools":[{"name":"sessions_read","input_schema":{}}]
                }""";

        UpstreamAnthropicOAuthMimicryNormalizer.Result result = normalizer.normalize(
                "req-1", account(), body, "claude-sonnet-4-5", clientProfile());
        JsonNode root = JSON.readTree(result.body());

        assertTrue(root.has(AnthropicMessagesBodyPolicy.FIELD_METADATA));
        assertTrue(root.get(AnthropicMessagesBodyPolicy.FIELD_METADATA)
                .has(AnthropicMessagesBodyPolicy.FIELD_USER_ID));
        assertTrue(root.get(AnthropicMessagesBodyPolicy.FIELD_SYSTEM).toString()
                .contains(AnthropicClaudeCodeProfile.BILLING_HEADER_NAME));
        assertTrue(root.get(AnthropicMessagesBodyPolicy.FIELD_SYSTEM).toString()
                .contains(AnthropicClaudeCodeProfile.CLAUDE_CODE_SYSTEM_PROMPT));
        assertTrue(root.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGES).toString()
                .contains(AnthropicClaudeCodeProfile.SYSTEM_INSTRUCTIONS_PREFIX.trim()));
        assertTrue(root.has(AnthropicMessagesBodyPolicy.FIELD_TOOLS));
        assertTrue(result.toolNameRewrite().hasRewrite());
    }

    @Test
    @DisplayName("metadata passthrough policy does not inject metadata user_id when absent")
    void metadataPassthroughDoesNotInjectMetadataUserIdWhenAbsent() throws Exception {
        UpstreamAnthropicOAuthMimicryNormalizer normalizer = new UpstreamAnthropicOAuthMimicryNormalizer(
                new OAuthMimicryService(),
                new FingerprintService(),
                new AnthropicForwardingRuntimePolicyProvider(true, true, false, ""));
        String body = """
                {
                  "model":"claude-haiku-4-5",
                  "messages":[{"role":"user","content":"Hello"}]
                }""";

        UpstreamAnthropicOAuthMimicryNormalizer.Result result = normalizer.normalize(
                "req-1", account(), body, "claude-haiku-4-5", clientProfile());
        JsonNode root = JSON.readTree(result.body());

        assertFalse(root.has(AnthropicMessagesBodyPolicy.FIELD_METADATA));
        assertTrue(root.has(AnthropicMessagesBodyPolicy.FIELD_TOOLS));
    }

    private static AccountEntity account() {
        return AccountEntity.builder()
                .id(1L)
                .platform(Platform.ANTHROPIC)
                .type(AccountType.OAUTH)
                .extra("{\"account_uuid\":\"account-selected\"}")
                .build();
    }

    private static ClientProfile clientProfile() {
        return new ClientProfile(
                Platform.ANTHROPIC,
                "messages",
                false,
                null,
                Map.of(AnthropicApiProfile.HEADER_USER_AGENT,
                        AnthropicClaudeCodeProfile.DEFAULT_CLAUDE_CLI_USER_AGENT));
    }
}
