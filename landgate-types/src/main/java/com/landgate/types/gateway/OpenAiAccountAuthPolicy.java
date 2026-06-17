package com.landgate.types.gateway;

import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;

/**
 * Stable OpenAI account-auth compatibility facts.
 *
 * <p>This policy owns only OpenAI account platform/type predicates. It must not
 * build headers, mutate requests, refresh credentials, or choose routes.</p>
 */
public final class OpenAiAccountAuthPolicy {

    private OpenAiAccountAuthPolicy() {
    }

    public static boolean isOAuthType(AccountType accountType) {
        return accountType == AccountType.OAUTH;
    }

    public static boolean isApiKeyType(AccountType accountType) {
        return accountType == AccountType.API_KEY;
    }

    public static boolean isOpenAiPlatform(Platform platform) {
        return platform == Platform.OPENAI;
    }

    public static boolean isOpenAiOAuth(Platform platform, AccountType accountType) {
        return isOpenAiPlatform(platform) && isOAuthType(accountType);
    }

    public static boolean isOpenAiApiKey(Platform platform, AccountType accountType) {
        return isOpenAiPlatform(platform) && isApiKeyType(accountType);
    }
}
