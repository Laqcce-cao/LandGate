package com.landgate.types.gateway;

import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Gateway auth failure policy tests")
class GatewayAuthFailurePolicyTest {

    @Test
    @DisplayName("401 status and account predicates are stable")
    void predicates() {
        assertTrue(GatewayAuthFailurePolicy.isUnauthorized(401));
        assertFalse(GatewayAuthFailurePolicy.isUnauthorized(403));

        assertTrue(GatewayAuthFailurePolicy.isNonOauthAccount(AccountType.API_KEY));
        assertFalse(GatewayAuthFailurePolicy.isNonOauthAccount(AccountType.OAUTH));

        assertTrue(GatewayAuthFailurePolicy.isOpenAiOauthAccount(Platform.OPENAI, AccountType.OAUTH));
        assertFalse(GatewayAuthFailurePolicy.isOpenAiOauthAccount(Platform.OPENAI, AccountType.API_KEY));
        assertFalse(GatewayAuthFailurePolicy.isOpenAiOauthAccount(Platform.ANTHROPIC, AccountType.OAUTH));
    }

    @Test
    @DisplayName("401 failover reasons and account-state messages are centralized")
    void reasonsAndMessages() {
        assertEquals("openai_401_permanent", GatewayAuthFailurePolicy.FAILOVER_OPENAI_401_PERMANENT);
        assertEquals("upstream_401_non_oauth", GatewayAuthFailurePolicy.FAILOVER_UPSTREAM_401_NON_OAUTH);
        assertEquals("openai_oauth_401_temp_unschedulable",
                GatewayAuthFailurePolicy.FAILOVER_OPENAI_OAUTH_401_TEMP_UNSCHEDULABLE);
        assertEquals("oauth_401_after_refresh", GatewayAuthFailurePolicy.FAILOVER_OAUTH_401_AFTER_REFRESH);
        assertEquals("oauth_token_refresh_failed", GatewayAuthFailurePolicy.FAILOVER_OAUTH_TOKEN_REFRESH_FAILED);

        assertEquals("Upstream returned 401 for API_KEY account",
                GatewayAuthFailurePolicy.nonOauthUnauthorizedAccountError(AccountType.API_KEY));
        assertEquals("OAuth token refreshed but upstream still returned 401",
                GatewayAuthFailurePolicy.oauthAfterRefreshAccountError());
        assertEquals("OAuth account has no refresh_token, cannot recover from 401",
                GatewayAuthFailurePolicy.oauthMissingRefreshTokenAccountError());
        assertEquals("OAuth token refresh temporarily failed",
                GatewayAuthFailurePolicy.oauthRefreshTemporaryFailureReason());
        assertEquals(600, GatewayAuthFailurePolicy.OAUTH_REFRESH_TEMP_UNSCHEDULABLE_SECONDS);
    }
}
