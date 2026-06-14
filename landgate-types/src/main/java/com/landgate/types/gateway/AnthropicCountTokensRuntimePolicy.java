package com.landgate.types.gateway;

import java.util.Set;

/**
 * Runtime switches for Anthropic Messages count_tokens forwarding.
 *
 * <p>Kept as a count_tokens compatibility alias. New shared forwarding code
 * should use {@link AnthropicForwardingRuntimePolicy} directly.</p>
 */
public record AnthropicCountTokensRuntimePolicy(
        boolean fingerprintUnification,
        boolean metadataPassthrough,
        boolean cchSigning,
        Set<String> betaDropTokens
) {
    public static final boolean DEFAULT_FINGERPRINT_UNIFICATION =
            AnthropicForwardingRuntimePolicy.DEFAULT_FINGERPRINT_UNIFICATION;
    public static final boolean DEFAULT_METADATA_PASSTHROUGH =
            AnthropicForwardingRuntimePolicy.DEFAULT_METADATA_PASSTHROUGH;
    public static final boolean DEFAULT_CCH_SIGNING =
            AnthropicForwardingRuntimePolicy.DEFAULT_CCH_SIGNING;

    public static final String PROPERTY_FINGERPRINT_UNIFICATION =
            AnthropicForwardingRuntimePolicy.PROPERTY_FINGERPRINT_UNIFICATION;
    public static final String PROPERTY_METADATA_PASSTHROUGH =
            AnthropicForwardingRuntimePolicy.PROPERTY_METADATA_PASSTHROUGH;
    public static final String PROPERTY_CCH_SIGNING =
            AnthropicForwardingRuntimePolicy.PROPERTY_CCH_SIGNING;
    public static final String PROPERTY_BETA_DROP_TOKENS =
            AnthropicForwardingRuntimePolicy.PROPERTY_BETA_DROP_TOKENS;

    public AnthropicCountTokensRuntimePolicy {
        betaDropTokens = normalizeDropTokens(betaDropTokens);
    }

    public static AnthropicCountTokensRuntimePolicy defaults() {
        return from(AnthropicForwardingRuntimePolicy.defaults());
    }

    public static Set<String> parseDropTokens(String value) {
        return AnthropicForwardingRuntimePolicy.parseDropTokens(value);
    }

    public static AnthropicCountTokensRuntimePolicy from(AnthropicForwardingRuntimePolicy policy) {
        AnthropicForwardingRuntimePolicy effective =
                policy == null ? AnthropicForwardingRuntimePolicy.defaults() : policy;
        return new AnthropicCountTokensRuntimePolicy(
                effective.fingerprintUnification(),
                effective.metadataPassthrough(),
                effective.cchSigning(),
                effective.betaDropTokens());
    }

    private static Set<String> normalizeDropTokens(Set<String> tokens) {
        return new AnthropicForwardingRuntimePolicy(
                DEFAULT_FINGERPRINT_UNIFICATION,
                DEFAULT_METADATA_PASSTHROUGH,
                DEFAULT_CCH_SIGNING,
                tokens).betaDropTokens();
    }
}
