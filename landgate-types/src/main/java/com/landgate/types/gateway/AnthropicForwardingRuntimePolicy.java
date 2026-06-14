package com.landgate.types.gateway;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runtime switches for Anthropic upstream forwarding.
 *
 * <p>Defaults mirror Sub2API's gateway forwarding settings: fingerprint
 * unification enabled, metadata passthrough disabled, CCH signing disabled,
 * and no beta tokens dropped unless a runtime policy provides them.</p>
 */
public record AnthropicForwardingRuntimePolicy(
        boolean fingerprintUnification,
        boolean metadataPassthrough,
        boolean cchSigning,
        Set<String> betaDropTokens
) {
    public static final boolean DEFAULT_FINGERPRINT_UNIFICATION = true;
    public static final boolean DEFAULT_METADATA_PASSTHROUGH = false;
    public static final boolean DEFAULT_CCH_SIGNING = false;

    public static final String PROPERTY_FINGERPRINT_UNIFICATION =
            "landgate.gateway.forwarding.fingerprint-unification";
    public static final String PROPERTY_METADATA_PASSTHROUGH =
            "landgate.gateway.forwarding.metadata-passthrough";
    public static final String PROPERTY_CCH_SIGNING =
            "landgate.gateway.forwarding.cch-signing";
    public static final String PROPERTY_BETA_DROP_TOKENS =
            "landgate.gateway.anthropic.beta-drop-tokens";

    public AnthropicForwardingRuntimePolicy {
        betaDropTokens = normalizeDropTokens(betaDropTokens);
    }

    public static AnthropicForwardingRuntimePolicy defaults() {
        return new AnthropicForwardingRuntimePolicy(
                DEFAULT_FINGERPRINT_UNIFICATION,
                DEFAULT_METADATA_PASSTHROUGH,
                DEFAULT_CCH_SIGNING,
                Set.of());
    }

    public static Set<String> parseDropTokens(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> normalizeDropTokens(Set<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return Set.of();
        }
        return tokens.stream()
                .map(token -> token == null ? "" : token.trim())
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
