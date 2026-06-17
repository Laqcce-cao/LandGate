package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAiEndpointPolicy 测试")
class OpenAiEndpointPolicyTest {

    @Test
    @DisplayName("OpenAI endpoint 基础事实集中维护")
    void endpointFactsAreCentralized() {
        assertEquals("https://api.openai.com", OpenAiEndpointPolicy.PUBLIC_API_BASE_URL);
        assertEquals("https://chatgpt.com", OpenAiEndpointPolicy.CODEX_BASE_URL);
        assertEquals("/v1", OpenAiEndpointPolicy.V1_PREFIX);
        assertEquals("/v1/chat/completions", OpenAiEndpointPolicy.V1_CHAT_COMPLETIONS_PATH);
        assertEquals("/chat/completions", OpenAiEndpointPolicy.CHAT_COMPLETIONS_ALIAS_PATH);
    }

    @Test
    @DisplayName("Chat Completions path 判断集中维护")
    void detectsChatCompletionsPaths() {
        assertTrue(OpenAiEndpointPolicy.isChatCompletionsPath("/v1/chat/completions"));
        assertTrue(OpenAiEndpointPolicy.isChatCompletionsPath("/v1/chat/completions?x=1"));
        assertTrue(OpenAiEndpointPolicy.isChatCompletionsPath("/chat/completions"));
        assertFalse(OpenAiEndpointPolicy.isChatCompletionsPath("/v1/responses"));
        assertFalse(OpenAiEndpointPolicy.isChatCompletionsPath(null));
    }
}
