package com.landgate.types.gateway;

import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;

/**
 * Stable gateway auth-failure facts for upstream 401 handling.
 *
 * <p>This policy owns only route/account auth classification, account-state
 * reason text, retry cooldown facts, and failover reason identifiers. It must
 * not refresh tokens, select accounts, mutate requests, or write responses.</p>
 */
public final class GatewayAuthFailurePolicy {

    public static final int STATUS_UNAUTHORIZED = 401;
    public static final long OAUTH_REFRESH_TEMP_UNSCHEDULABLE_SECONDS = 600;

    public static final String FAILOVER_OPENAI_401_PERMANENT = "openai_401_permanent";
    public static final String FAILOVER_UPSTREAM_401_NON_OAUTH = "upstream_401_non_oauth";
    public static final String FAILOVER_OPENAI_OAUTH_401_TEMP_UNSCHEDULABLE =
            "openai_oauth_401_temp_unschedulable";
    public static final String FAILOVER_OAUTH_401_AFTER_REFRESH = "oauth_401_after_refresh";
    public static final String FAILOVER_OAUTH_TOKEN_REFRESH_FAILED = "oauth_token_refresh_failed";

    private GatewayAuthFailurePolicy() {
    }

    public static boolean isUnauthorized(int statusCode) {
        return statusCode == STATUS_UNAUTHORIZED;
    }

    public static boolean isNonOauthAccount(AccountType accountType) {
        return accountType != AccountType.OAUTH;
    }

    public static boolean isOpenAiOauthAccount(Platform platform, AccountType accountType) {
        return platform == Platform.OPENAI && accountType == AccountType.OAUTH;
    }

    public static String nonOauthUnauthorizedAccountError(AccountType accountType) {
        return "Upstream returned 401 for " + accountType + " account";
    }

    public static String oauthAfterRefreshAccountError() {
        return "OAuth token refreshed but upstream still returned 401";
    }

    public static String oauthMissingRefreshTokenAccountError() {
        return "OAuth account has no refresh_token, cannot recover from 401";
    }

    public static String oauthRefreshTemporaryFailureReason() {
        return "OAuth token refresh temporarily failed";
    }
}
