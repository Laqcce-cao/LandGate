package com.landgate.types.gateway;

import java.util.Locale;
import java.util.Set;

/**
 * Stable Anthropic upstream header allowlists split by upstream trust domain.
 *
 * <p>This type owns header allowlist facts only. It must not build auth
 * headers, choose routes, normalize bodies, or copy client headers.</p>
 */
public final class AnthropicHeaderPolicy {

    public static final Set<String> API_KEY_PASSTHROUGH_ALLOWED_HEADERS = Set.of(
            headerKey(AnthropicApiProfile.HEADER_ACCEPT),
            headerKey(AnthropicApiProfile.HEADER_ACCEPT_ENCODING),
            headerKey(AnthropicApiProfile.HEADER_ACCEPT_LANGUAGE),
            headerKey(AnthropicApiProfile.HEADER_ANTHROPIC_BETA),
            headerKey(AnthropicApiProfile.HEADER_ANTHROPIC_VERSION),
            headerKey(AnthropicApiProfile.HEADER_CONTENT_TYPE),
            headerKey(AnthropicApiProfile.HEADER_SEC_FETCH_MODE),
            headerKey(AnthropicApiProfile.HEADER_STAINLESS_HELPER_METHOD),
            headerKey(AnthropicApiProfile.HEADER_USER_AGENT),
            headerKey(AnthropicApiProfile.HEADER_X_APP),
            headerKey(AnthropicApiProfile.HEADER_X_CLAUDE_CODE_SESSION_ID),
            headerKey(AnthropicApiProfile.HEADER_X_CLIENT_REQUEST_ID),
            headerKey(AnthropicClaudeCodeProfile.HEADER_ANTHROPIC_DANGEROUS_DIRECT_BROWSER_ACCESS),
            headerKey(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_ARCH),
            headerKey(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_LANG),
            headerKey(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_OS),
            headerKey(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_PACKAGE_VERSION),
            headerKey(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_RETRY_COUNT),
            headerKey(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_RUNTIME),
            headerKey(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_RUNTIME_VERSION),
            headerKey(AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_TIMEOUT));

    private AnthropicHeaderPolicy() {
    }

    public static String headerKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
