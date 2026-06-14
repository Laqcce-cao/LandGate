package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAiChatCompletionsBodyPolicy 测试")
class OpenAiChatCompletionsBodyPolicyTest {

    @Test
    @DisplayName("Chat Completions 字段和稳定值集中维护")
    void chatCompletionsFactsAreCentralized() {
        assertEquals("messages", OpenAiChatCompletionsBodyPolicy.FIELD_MESSAGES);
        assertEquals("stream_options", OpenAiChatCompletionsBodyPolicy.FIELD_STREAM_OPTIONS);
        assertEquals("include_usage", OpenAiChatCompletionsBodyPolicy.FIELD_INCLUDE_USAGE);
        assertEquals("tool_calls", OpenAiChatCompletionsBodyPolicy.FIELD_TOOL_CALLS);
        assertEquals("finish_reason", OpenAiChatCompletionsBodyPolicy.FIELD_FINISH_REASON);
        assertEquals("reasoning", OpenAiChatCompletionsBodyPolicy.FIELD_REASONING);
        assertEquals("effort", OpenAiChatCompletionsBodyPolicy.FIELD_EFFORT);
        assertEquals("allowed_tools", OpenAiChatCompletionsBodyPolicy.FIELD_ALLOWED_TOOLS);
        assertEquals("search_context_size", OpenAiChatCompletionsBodyPolicy.FIELD_SEARCH_CONTEXT_SIZE);
        assertEquals("user_location", OpenAiChatCompletionsBodyPolicy.FIELD_USER_LOCATION);
        assertEquals("approximate", OpenAiChatCompletionsBodyPolicy.FIELD_APPROXIMATE);
        assertEquals("chat.completion", OpenAiChatCompletionsBodyPolicy.OBJECT_CHAT_COMPLETION);
        assertEquals("chat.completion.chunk", OpenAiChatCompletionsBodyPolicy.OBJECT_CHAT_COMPLETION_CHUNK);
        assertEquals("assistant", OpenAiChatCompletionsBodyPolicy.ROLE_ASSISTANT);
        assertEquals("function", OpenAiChatCompletionsBodyPolicy.TYPE_FUNCTION);
        assertEquals("tool_calls", OpenAiChatCompletionsBodyPolicy.FINISH_REASON_TOOL_CALLS);
        assertEquals("chatcmpl-", OpenAiChatCompletionsBodyPolicy.ID_PREFIX_CHAT_COMPLETION);
        assertEquals("call_", OpenAiChatCompletionsBodyPolicy.ID_PREFIX_TOOL_CALL);
    }

    @Test
    @DisplayName("Chat Completions 枚举判断集中维护")
    void chatCompletionsEnumPolicyIsCentralized() {
        assertTrue(OpenAiChatCompletionsBodyPolicy.isSupportedToolChoiceMode("auto"));
        assertTrue(OpenAiChatCompletionsBodyPolicy.isSupportedToolChoiceMode("none"));
        assertTrue(OpenAiChatCompletionsBodyPolicy.isSupportedToolChoiceMode("required"));
        assertFalse(OpenAiChatCompletionsBodyPolicy.isSupportedToolChoiceMode("any"));

        assertTrue(OpenAiChatCompletionsBodyPolicy.isSupportedLegacyFunctionCallMode("auto"));
        assertFalse(OpenAiChatCompletionsBodyPolicy.isSupportedLegacyFunctionCallMode("required"));

        assertTrue(OpenAiChatCompletionsBodyPolicy.isIncompleteFinishReason("length"));
        assertTrue(OpenAiChatCompletionsBodyPolicy.isIncompleteFinishReason("content_filter"));
        assertFalse(OpenAiChatCompletionsBodyPolicy.isIncompleteFinishReason("stop"));
    }

    @Test
    @DisplayName("Chat Completions 兼容选项规范化集中维护")
    void chatCompletionsCompatibilityValueNormalizationIsCentralized() {
        assertNull(OpenAiChatCompletionsBodyPolicy.normalizeReasoningEffort("minimal"));
        assertNull(OpenAiChatCompletionsBodyPolicy.normalizeReasoningEffort("none"));
        assertEquals("high", OpenAiChatCompletionsBodyPolicy.normalizeReasoningEffort(" HIGH "));
        assertEquals("xhigh", OpenAiChatCompletionsBodyPolicy.normalizeReasoningEffort("x-high"));
        assertEquals("xhigh", OpenAiChatCompletionsBodyPolicy.normalizeReasoningEffort("extra_high"));
        assertEquals("xhigh", OpenAiChatCompletionsBodyPolicy.normalizeReasoningEffort("xhigh"));
        assertNull(OpenAiChatCompletionsBodyPolicy.normalizeReasoningEffort("extreme"));

        assertEquals("medium", OpenAiChatCompletionsBodyPolicy.normalizeTextVerbosity("medium"));
        assertNull(OpenAiChatCompletionsBodyPolicy.normalizeTextVerbosity("verbose"));

        assertEquals("auto", OpenAiChatCompletionsBodyPolicy.normalizeImageDetail("auto"));
        assertNull(OpenAiChatCompletionsBodyPolicy.normalizeImageDetail("tiny"));

        assertEquals("high", OpenAiChatCompletionsBodyPolicy.normalizeSearchContextSize("high"));
        assertNull(OpenAiChatCompletionsBodyPolicy.normalizeSearchContextSize("large"));

        assertEquals("regex", OpenAiChatCompletionsBodyPolicy.normalizeCustomToolGrammarSyntax("regex"));
        assertEquals("lark", OpenAiChatCompletionsBodyPolicy.normalizeCustomToolGrammarSyntax("lark"));
        assertNull(OpenAiChatCompletionsBodyPolicy.normalizeCustomToolGrammarSyntax("json"));
    }
}
