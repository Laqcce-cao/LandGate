package com.landgate.trigger.gateway.forwarding;

import com.landgate.types.gateway.AnthropicForwardingRuntimePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Supplies Sub2API-compatible Anthropic gateway forwarding switches.
 *
 * <p>This provider owns configuration binding only. Body mutation, header
 * construction, routing, and protocol conversion stay in their own modules.</p>
 */
@Component
public class AnthropicForwardingRuntimePolicyProvider {

    private final boolean fingerprintUnification;
    private final boolean metadataPassthrough;
    private final boolean cchSigning;
    private final String betaDropTokens;

    public AnthropicForwardingRuntimePolicyProvider(
            @Value("${" + AnthropicForwardingRuntimePolicy.PROPERTY_FINGERPRINT_UNIFICATION
                    + ":" + AnthropicForwardingRuntimePolicy.DEFAULT_FINGERPRINT_UNIFICATION + "}")
            boolean fingerprintUnification,
            @Value("${" + AnthropicForwardingRuntimePolicy.PROPERTY_METADATA_PASSTHROUGH
                    + ":" + AnthropicForwardingRuntimePolicy.DEFAULT_METADATA_PASSTHROUGH + "}")
            boolean metadataPassthrough,
            @Value("${" + AnthropicForwardingRuntimePolicy.PROPERTY_CCH_SIGNING
                    + ":" + AnthropicForwardingRuntimePolicy.DEFAULT_CCH_SIGNING + "}")
            boolean cchSigning,
            @Value("${" + AnthropicForwardingRuntimePolicy.PROPERTY_BETA_DROP_TOKENS + ":}")
            String betaDropTokens) {
        this.fingerprintUnification = fingerprintUnification;
        this.metadataPassthrough = metadataPassthrough;
        this.cchSigning = cchSigning;
        this.betaDropTokens = betaDropTokens;
    }

    public AnthropicForwardingRuntimePolicy current() {
        return new AnthropicForwardingRuntimePolicy(
                fingerprintUnification,
                metadataPassthrough,
                cchSigning,
                AnthropicForwardingRuntimePolicy.parseDropTokens(betaDropTokens));
    }
}
