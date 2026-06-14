package com.landgate.trigger.gateway.transformer;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("AnthropicAuthProfile 测试")
class AnthropicAuthProfileTest {

    @Test
    @DisplayName("API Key 账号使用 x-api-key 且不生成 Bearer")
    void apiKeyUsesXApiKey() {
        Map<String, String> headers = headersFor(AccountType.API_KEY);

        assertEquals("token-1", headers.get("x-api-key"));
        assertEquals("2023-06-01", headers.get("anthropic-version"));
        assertFalse(headers.containsKey("Authorization"));
        assertFalse(headers.containsKey("anthropic-beta"));
    }

    @Test
    @DisplayName("OAuth 账号使用 Bearer 和 OAuth beta")
    void oauthUsesBearerAndOauthBeta() {
        Map<String, String> headers = headersFor(AccountType.OAUTH);

        assertEquals("Bearer token-1", headers.get("Authorization"));
        assertEquals("2023-06-01", headers.get("anthropic-version"));
        assertEquals("oauth-2025-04-20", headers.get("anthropic-beta"));
        assertFalse(headers.containsKey("x-api-key"));
    }

    @Test
    @DisplayName("Setup token 账号按 OAuth 上游认证处理")
    void setupTokenUsesOauthProfile() {
        Map<String, String> headers = headersFor(AccountType.SETUP_TOKEN);

        assertEquals("Bearer token-1", headers.get("Authorization"));
        assertEquals("oauth-2025-04-20", headers.get("anthropic-beta"));
        assertFalse(headers.containsKey("x-api-key"));
    }

    private static Map<String, String> headersFor(AccountType type) {
        AccountEntity account = AccountEntity.builder()
                .id(1L)
                .platform(Platform.ANTHROPIC)
                .type(type)
                .build();
        String[] pairs = AnthropicAuthProfile.from(account).buildHeaders("token-1");
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            headers.put(pairs[i], pairs[i + 1]);
        }
        return headers;
    }
}
