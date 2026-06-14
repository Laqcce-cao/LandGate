package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAiCodexBodyShapeProfile 测试")
class OpenAiCodexBodyShapeProfileTest {

    @Test
    @DisplayName("Codex tool_choice 文本值集中维护")
    void allowedTextToolChoicesAreCentralized() {
        assertTrue(OpenAiCodexBodyShapeProfile.isAllowedTextToolChoice(
                OpenAiCodexBodyShapeProfile.TOOL_CHOICE_AUTO));
        assertTrue(OpenAiCodexBodyShapeProfile.isAllowedTextToolChoice(
                OpenAiCodexBodyShapeProfile.TOOL_CHOICE_REQUIRED));
        assertTrue(OpenAiCodexBodyShapeProfile.isAllowedTextToolChoice(
                OpenAiCodexBodyShapeProfile.TOOL_CHOICE_NONE));
        assertFalse(OpenAiCodexBodyShapeProfile.isAllowedTextToolChoice("missing_tool"));
    }

    @Test
    @DisplayName("Codex input item 类型策略集中维护")
    void codexInputItemTypePoliciesAreCentralized() {
        assertTrue(OpenAiCodexBodyShapeProfile.isCodexToolCallItemType(
                OpenAiCodexBodyShapeProfile.TYPE_FUNCTION_CALL));
        assertTrue(OpenAiCodexBodyShapeProfile.isCodexToolCallItemType(
                OpenAiCodexBodyShapeProfile.TYPE_TOOL_SEARCH_OUTPUT));
        assertFalse(OpenAiCodexBodyShapeProfile.isCodexToolCallItemType(
                OpenAiCodexBodyShapeProfile.TYPE_MESSAGE));

        assertTrue(OpenAiCodexBodyShapeProfile.codexInputItemRequiresName(
                OpenAiCodexBodyShapeProfile.TYPE_FUNCTION_CALL));
        assertTrue(OpenAiCodexBodyShapeProfile.codexInputItemRequiresName(
                OpenAiCodexBodyShapeProfile.TYPE_MCP_TOOL_CALL));
        assertFalse(OpenAiCodexBodyShapeProfile.codexInputItemRequiresName(
                OpenAiCodexBodyShapeProfile.TYPE_FUNCTION_CALL_OUTPUT));
    }

    @Test
    @DisplayName("system/developer role 判断集中维护")
    void systemOrDeveloperRolePolicyIsCentralized() {
        assertTrue(OpenAiCodexBodyShapeProfile.isSystemOrDeveloperRole(
                OpenAiCodexBodyShapeProfile.ROLE_SYSTEM));
        assertTrue(OpenAiCodexBodyShapeProfile.isSystemOrDeveloperRole(
                OpenAiCodexBodyShapeProfile.ROLE_DEVELOPER));
        assertFalse(OpenAiCodexBodyShapeProfile.isSystemOrDeveloperRole(
                OpenAiCodexBodyShapeProfile.ROLE_USER));
    }

    @Test
    @DisplayName("Codex call_id 前缀集中维护")
    void codexCallIdPrefixesAreCentralized() {
        assertEquals(OpenAiChatCompletionsBodyPolicy.ID_PREFIX_TOOL_CALL,
                OpenAiCodexBodyShapeProfile.CALL_ID_PREFIX_CALL);
        assertEquals("fc", OpenAiCodexBodyShapeProfile.CALL_ID_PREFIX_FC);
        assertEquals("fc_", OpenAiCodexBodyShapeProfile.CALL_ID_PREFIX_FC_UNDERSCORE);
    }
}
