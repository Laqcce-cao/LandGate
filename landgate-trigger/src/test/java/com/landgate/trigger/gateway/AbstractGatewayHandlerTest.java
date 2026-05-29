package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AbstractGatewayHandler 单元测试 —— 验证网关通用请求决策逻辑。
 */
@DisplayName("AbstractGatewayHandler 测试")
class AbstractGatewayHandlerTest {

    @Test
    @DisplayName("OpenAI OAuth Codex 账号强制按流式响应处理")
    void openAiOAuthCodexAccountForcesStreaming() {
        AccountEntity account = AccountEntity.builder()
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .build();
        String body = """
                {
                  "model":"gpt-5.5",
                  "stream":false,
                  "messages":[{"role":"user","content":"Hi"}]
                }""";

        assertTrue(AbstractGatewayHandler.shouldHandleAsStreaming("chat_completions", body, account));
    }

    @Test
    @DisplayName("普通 OpenAI API Key 账号保留客户端非流式选择")
    void openAiApiKeyAccountKeepsClientNonStreamingChoice() {
        AccountEntity account = AccountEntity.builder()
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .build();
        String body = """
                {
                  "model":"gpt-5.5",
                  "stream":false,
                  "messages":[{"role":"user","content":"Hi"}]
                }""";

        assertFalse(AbstractGatewayHandler.shouldHandleAsStreaming("chat_completions", body, account));
    }

    @Test
    @DisplayName("Responses 客户端格式默认按流式响应处理")
    void responsesRequestFormatDefaultsToStreaming() {
        AccountEntity account = AccountEntity.builder()
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .build();
        String body = """
                {
                  "model":"gpt-5.5",
                  "input":"Hi"
                }""";

        assertTrue(AbstractGatewayHandler.shouldHandleAsStreaming("responses", body, account));
    }
}
