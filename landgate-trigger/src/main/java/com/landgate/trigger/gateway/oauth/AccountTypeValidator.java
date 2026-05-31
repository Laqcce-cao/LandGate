package com.landgate.trigger.gateway.oauth;

import com.landgate.types.enums.Platform;
import com.landgate.types.enums.AccountType;

import java.util.Map;
import java.util.Set;

/**
 * 平台-账号类型合法性校验。
 * <p>
 * 参照：sub2api 各平台支持的账号类型矩阵。
 */
public final class AccountTypeValidator {

    private static final Map<Platform, Set<AccountType>> PLATFORM_ALLOWED_TYPES = Map.of(
            Platform.ANTHROPIC, Set.of(
                    AccountType.OAUTH, AccountType.SETUP_TOKEN, AccountType.API_KEY,
                    AccountType.UPSTREAM, AccountType.BEDROCK, AccountType.SERVICE_ACCOUNT),
            Platform.OPENAI, Set.of(
                    AccountType.OAUTH, AccountType.API_KEY, AccountType.UPSTREAM),
            Platform.GEMINI, Set.of(
                    AccountType.OAUTH, AccountType.API_KEY, AccountType.SERVICE_ACCOUNT),
            Platform.ANTIGRAVITY, Set.of(
                    AccountType.OAUTH, AccountType.API_KEY, AccountType.UPSTREAM)
    );

    private AccountTypeValidator() {}

    /**
     * 校验 platform + accountType 组合是否合法。
     *
     * @throws IllegalArgumentException 如果组合不支持
     */
    public static void validate(Platform platform, AccountType type) {
        if (platform == null || type == null) return;
        Set<AccountType> allowed = PLATFORM_ALLOWED_TYPES.get(platform);
        if (allowed == null || !allowed.contains(type)) {
            throw new IllegalArgumentException(
                    "Account type '" + type.getKey() + "' is not supported for platform '"
                            + platform.getKey() + "'");
        }
    }
}
