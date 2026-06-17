package com.landgate.types.gateway;

import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenAI account auth policy tests")
class OpenAiAccountAuthPolicyTest {

    @Test
    @DisplayName("OpenAI account type predicates are stable")
    void accountTypes() {
        assertTrue(OpenAiAccountAuthPolicy.isOAuthType(AccountType.OAUTH));
        assertFalse(OpenAiAccountAuthPolicy.isOAuthType(AccountType.API_KEY));

        assertTrue(OpenAiAccountAuthPolicy.isApiKeyType(AccountType.API_KEY));
        assertFalse(OpenAiAccountAuthPolicy.isApiKeyType(AccountType.OAUTH));
    }

    @Test
    @DisplayName("OpenAI platform and platform+type predicates are stable")
    void platformAndType() {
        assertTrue(OpenAiAccountAuthPolicy.isOpenAiPlatform(Platform.OPENAI));
        assertFalse(OpenAiAccountAuthPolicy.isOpenAiPlatform(Platform.ANTHROPIC));

        assertTrue(OpenAiAccountAuthPolicy.isOpenAiOAuth(Platform.OPENAI, AccountType.OAUTH));
        assertFalse(OpenAiAccountAuthPolicy.isOpenAiOAuth(Platform.OPENAI, AccountType.API_KEY));
        assertFalse(OpenAiAccountAuthPolicy.isOpenAiOAuth(Platform.ANTHROPIC, AccountType.OAUTH));

        assertTrue(OpenAiAccountAuthPolicy.isOpenAiApiKey(Platform.OPENAI, AccountType.API_KEY));
        assertFalse(OpenAiAccountAuthPolicy.isOpenAiApiKey(Platform.OPENAI, AccountType.OAUTH));
        assertFalse(OpenAiAccountAuthPolicy.isOpenAiApiKey(Platform.ANTHROPIC, AccountType.API_KEY));
    }
}
