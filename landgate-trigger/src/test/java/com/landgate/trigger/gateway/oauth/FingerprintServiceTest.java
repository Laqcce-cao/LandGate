package com.landgate.trigger.gateway.oauth;

import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("Fingerprint service tests")
class FingerprintServiceTest {

    @Test
    @DisplayName("Reads client User-Agent case-insensitively for Claude OAuth compatibility")
    void readsClientUserAgentCaseInsensitively() {
        FingerprintService service = new FingerprintService();

        var fingerprint = service.getOrCreateFingerprint(1L,
                Map.of(
                        "user-agent", " claude-cli/2.1.78 (external, cli) ",
                        "x-stainless-os", "Darwin",
                        "X-Stainless-Arch", "x64"
                ));

        assertEquals("claude-cli/2.1.78 (external, cli)", fingerprint.getUserAgent());
        assertEquals("Darwin", fingerprint.getStainlessOs());
        assertEquals("x64", fingerprint.getStainlessArch());
        assertEquals(AnthropicClaudeCodeProfile.DEFAULT_MIMICRY_HEADERS.get(
                AnthropicClaudeCodeProfile.HEADER_X_STAINLESS_LANG), fingerprint.getStainlessLang());
        assertFalse(fingerprint.getClientId().isBlank());
    }

    @Test
    @DisplayName("Falls back to default Claude CLI User-Agent when client header is absent")
    void fallsBackToDefaultUserAgentWhenAbsent() {
        FingerprintService service = new FingerprintService();

        var fingerprint = service.getOrCreateFingerprint(1L, Map.of());

        assertEquals(ClaudeConstants.DEFAULT_CLAUDE_CLI_USER_AGENT, fingerprint.getUserAgent());
    }

    @Test
    @DisplayName("Updates cached fingerprint on newer Claude CLI version and preserves missing fields")
    void updatesCachedFingerprintOnNewerVersionAndPreservesMissingFields() {
        FingerprintService service = new FingerprintService();
        var first = service.getOrCreateFingerprint(1L, Map.of(
                "User-Agent", "claude-cli/2.1.78 (external, cli)",
                "X-Stainless-OS", "Darwin",
                "X-Stainless-Arch", "x64",
                "X-Stainless-Runtime-Version", "v22.0.0"
        ));

        var updated = service.getOrCreateFingerprint(1L, Map.of(
                "User-Agent", "claude-cli/2.1.92 (external, cli)",
                "X-Stainless-OS", "Linux"
        ));

        assertEquals(first.getClientId(), updated.getClientId());
        assertEquals("claude-cli/2.1.92 (external, cli)", updated.getUserAgent());
        assertEquals("Linux", updated.getStainlessOs());
        assertEquals("x64", updated.getStainlessArch());
        assertEquals("v22.0.0", updated.getStainlessRuntimeVersion());
    }

    @Test
    @DisplayName("Does not update cached fingerprint for lower or different-product User-Agent")
    void doesNotUpdateCachedFingerprintForLowerOrDifferentProductUserAgent() {
        FingerprintService service = new FingerprintService();
        var first = service.getOrCreateFingerprint(1L,
                Map.of("User-Agent", "claude-cli/2.1.92 (external, cli)"));

        var lower = service.getOrCreateFingerprint(1L,
                Map.of("User-Agent", "claude-cli/2.1.78 (external, cli)"));
        var browser = service.getOrCreateFingerprint(1L,
                Map.of("User-Agent", "Mozilla/99.0"));

        assertEquals(first.getClientId(), lower.getClientId());
        assertEquals("claude-cli/2.1.92 (external, cli)", lower.getUserAgent());
        assertEquals(first.getClientId(), browser.getClientId());
        assertEquals("claude-cli/2.1.92 (external, cli)", browser.getUserAgent());
    }
}
