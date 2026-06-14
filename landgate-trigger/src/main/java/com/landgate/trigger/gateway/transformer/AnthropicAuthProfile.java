package com.landgate.trigger.gateway.transformer;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.enums.AccountType;
import com.landgate.types.gateway.AnthropicApiProfile;
import com.landgate.types.gateway.AnthropicHeaderPolicy;

import java.util.Map;

/**
 * Anthropic upstream auth/header profile.
 */
enum AnthropicAuthProfile {

    API_KEY {
        @Override
        void appendCredentialHeaders(UpstreamHeaders headers, String accessToken) {
            headers.set(AnthropicApiProfile.HEADER_X_API_KEY, accessToken);
        }

        @Override
        void appendCompatibilityHeaders(UpstreamHeaders headers, Map<String, String> requestHeaders) {
            headers.copyAllowed(requestHeaders, AnthropicHeaderPolicy.API_KEY_PASSTHROUGH_ALLOWED_HEADERS);
        }
    },

    OAUTH {
        @Override
        void appendCredentialHeaders(UpstreamHeaders headers, String accessToken) {
            headers.set(AnthropicApiProfile.HEADER_AUTHORIZATION, AnthropicApiProfile.bearerToken(accessToken));
        }

        @Override
        void appendCompatibilityHeaders(UpstreamHeaders headers, Map<String, String> requestHeaders) {
            headers.set(AnthropicApiProfile.HEADER_ANTHROPIC_BETA, AnthropicApiProfile.BETA_OAUTH);
        }
    };

    static AnthropicAuthProfile from(AccountEntity account) {
        if (account != null
                && (account.getType() == AccountType.OAUTH || account.getType() == AccountType.SETUP_TOKEN)) {
            return OAUTH;
        }
        return API_KEY;
    }

    String[] buildHeaders(String accessToken) {
        return buildHeaders(accessToken, Map.of());
    }

    String[] buildHeaders(String accessToken, Map<String, String> requestHeaders) {
        var headers = new UpstreamHeaders();
        appendCredentialHeaders(headers, accessToken);
        headers.set(AnthropicApiProfile.HEADER_ANTHROPIC_VERSION, AnthropicApiProfile.ANTHROPIC_VERSION);
        appendCompatibilityHeaders(headers, requestHeaders);
        return headers.toArray();
    }

    abstract void appendCredentialHeaders(UpstreamHeaders headers, String accessToken);

    void appendCompatibilityHeaders(UpstreamHeaders headers, Map<String, String> requestHeaders) {
    }
}
