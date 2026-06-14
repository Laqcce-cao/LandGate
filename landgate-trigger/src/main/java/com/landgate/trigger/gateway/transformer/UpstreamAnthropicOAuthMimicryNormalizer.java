package com.landgate.trigger.gateway.transformer;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.client.ClientProfile;
import com.landgate.trigger.gateway.forwarding.AnthropicForwardingRuntimePolicyProvider;
import com.landgate.trigger.gateway.oauth.AnthropicToolNameRewrite;
import com.landgate.trigger.gateway.oauth.FingerprintService;
import com.landgate.trigger.gateway.oauth.OAuthMimicryService;
import com.landgate.types.gateway.AnthropicForwardingRuntimePolicy;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies Anthropic OAuth Claude Code body-side mimicry after protocol
 * translation has produced an Anthropic Messages upstream body.
 *
 * <p>This class owns only the Phase A body normalization chain. Header
 * construction remains in {@link AnthropicTransformer}, protocol translation
 * remains in the converter layer, and account/route selection stays in the
 * handler.</p>
 */
@Slf4j
public final class UpstreamAnthropicOAuthMimicryNormalizer {

    private final OAuthMimicryService oAuthMimicryService;
    private final FingerprintService fingerprintService;
    private final AnthropicForwardingRuntimePolicyProvider runtimePolicyProvider;

    public UpstreamAnthropicOAuthMimicryNormalizer(
            OAuthMimicryService oAuthMimicryService,
            FingerprintService fingerprintService,
            AnthropicForwardingRuntimePolicyProvider runtimePolicyProvider) {
        this.oAuthMimicryService = oAuthMimicryService;
        this.fingerprintService = fingerprintService;
        this.runtimePolicyProvider = runtimePolicyProvider;
    }

    public Result normalize(String requestId,
                            AccountEntity account,
                            String body,
                            String model,
                            ClientProfile clientProfile) {
        if (account == null || account.getId() == null
                || oAuthMimicryService == null || fingerprintService == null) {
            return Result.unchanged(body);
        }

        AnthropicForwardingRuntimePolicy forwardingPolicy = currentRuntimePolicy();
        String normalized = body;
        boolean systemRewritten = false;

        log.debug("[{}] OAuth 伪装: account_id={}, model={}, type={}",
                requestId, account != null ? account.getId() : null, model,
                account != null ? account.getType() : null);

        if (model != null && !model.toLowerCase().contains("haiku")) {
            normalized = oAuthMimicryService.rewriteSystemForNonClaudeCode(normalized, model);
            systemRewritten = true;
        }

        FingerprintService.ClientFingerprint fingerprint = fingerprintService.getOrCreateFingerprint(
                account.getId(),
                clientProfile == null ? java.util.Map.of() : clientProfile.headers());
        if (!forwardingPolicy.metadataPassthrough()) {
            normalized = oAuthMimicryService.buildAndInjectMetadataUserID(
                    normalized, account, fingerprint, !systemRewritten);
        }
        normalized = oAuthMimicryService.normalizeClaudeOAuthRequestBody(
                normalized, model, false, null, !systemRewritten);
        OAuthMimicryService.ClaudeOAuthBodyMimicryResult mimicryResult =
                oAuthMimicryService.applyPostNormalizeClaudeOAuthMimicry(normalized);
        return new Result(mimicryResult.body(), mimicryResult.toolNameRewrite());
    }

    private AnthropicForwardingRuntimePolicy currentRuntimePolicy() {
        return runtimePolicyProvider == null
                ? AnthropicForwardingRuntimePolicy.defaults()
                : runtimePolicyProvider.current();
    }

    public record Result(String body, AnthropicToolNameRewrite toolNameRewrite) {
        public static Result unchanged(String body) {
            return new Result(body, AnthropicToolNameRewrite.empty());
        }
    }
}
