package com.landgate.types.gateway;

import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;

/**
 * Stable Anthropic account-auth compatibility facts.
 *
 * <p>This policy owns only account platform/type predicates used by Anthropic
 * OAuth/setup-token compatibility. It must not build headers, mutate request
 * bodies, refresh credentials, or select accounts.</p>
 */
public final class AnthropicAccountAuthPolicy {

    private AnthropicAccountAuthPolicy() {
    }

    public static boolean isAnthropicPlatform(Platform platform) {
        return platform == Platform.ANTHROPIC;
    }

    public static boolean isOAuthOrSetupTokenType(AccountType accountType) {
        return accountType == AccountType.OAUTH || accountType == AccountType.SETUP_TOKEN;
    }

    public static boolean isAnthropicOAuthOrSetupToken(Platform platform, AccountType accountType) {
        return isAnthropicPlatform(platform) && isOAuthOrSetupTokenType(accountType);
    }

    public static boolean shouldMimicClaudeCode(Platform platform,
                                                AccountType accountType,
                                                boolean clientIsClaudeCode) {
        return isAnthropicOAuthOrSetupToken(platform, accountType) && !clientIsClaudeCode;
    }
}
