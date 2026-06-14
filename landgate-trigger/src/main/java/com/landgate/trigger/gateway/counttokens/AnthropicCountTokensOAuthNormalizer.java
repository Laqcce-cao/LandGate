package com.landgate.trigger.gateway.counttokens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.oauth.BillingHeaderService;
import com.landgate.trigger.gateway.oauth.FingerprintService;
import com.landgate.trigger.gateway.oauth.OAuthMimicryService;
import com.landgate.trigger.gateway.oauth.UserIdRewriter;
import com.landgate.types.enums.AccountType;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import com.landgate.types.gateway.AnthropicForwardingRuntimePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Applies Anthropic OAuth count_tokens request-body compatibility.
 *
 * <p>This mirrors the body-side parts of Sub2API's count_tokens path. Header
 * defaults and beta selection remain owned by the request factory/profile.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicCountTokensOAuthNormalizer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FingerprintService fingerprintService;
    private final UserIdRewriter userIdRewriter;
    private final BillingHeaderService billingHeaderService;
    private final OAuthMimicryService oAuthMimicryService;

    public Result normalize(AccountEntity account,
                            String body,
                            String model,
                            boolean mimicClaudeCode,
                            Map<String, String> requestHeaders) {
        return normalize(account, body, model, mimicClaudeCode, requestHeaders,
                AnthropicForwardingRuntimePolicy.defaults());
    }

    public Result normalize(AccountEntity account,
                            String body,
                            String model,
                            boolean mimicClaudeCode,
                            Map<String, String> requestHeaders,
                            AnthropicForwardingRuntimePolicy policy) {
        if (!isAnthropicOAuth(account) || body == null || body.isBlank()) {
            return new Result(body, mimicClaudeCode, null);
        }
        AnthropicForwardingRuntimePolicy effective =
                policy == null ? AnthropicForwardingRuntimePolicy.defaults() : policy;

        FingerprintService.ClientFingerprint fingerprint =
                fingerprintService.getOrCreateFingerprint(account.getId(), requestHeaders);
        String normalized = body;

        String accountUuid = extractAccountUuid(account);
        if (!effective.metadataPassthrough()
                && !accountUuid.isBlank()
                && fingerprint != null
                && !fingerprint.getClientId().isBlank()) {
            normalized = userIdRewriter.rewriteUserIDWithMasking(
                    normalized, account, accountUuid, fingerprint.getClientId(), fingerprint.getUserAgent());
        }

        if (effective.fingerprintUnification() && fingerprint != null) {
            normalized = billingHeaderService.syncBillingHeaderVersion(normalized, fingerprint.getUserAgent());
        }

        if (mimicClaudeCode) {
            normalized = oAuthMimicryService.normalizeClaudeOAuthRequestBody(
                    normalized, model, false, null, true);
            normalized = oAuthMimicryService.applyPostNormalizeClaudeOAuthMimicry(normalized).body();
        }

        if (effective.cchSigning()) {
            normalized = billingHeaderService.signBillingHeaderCCH(normalized);
        }

        return new Result(
                normalized,
                mimicClaudeCode,
                effective.fingerprintUnification() ? fingerprint : null);
    }

    private static boolean isAnthropicOAuth(AccountEntity account) {
        return account != null
                && (account.getType() == AccountType.OAUTH || account.getType() == AccountType.SETUP_TOKEN);
    }

    private static String extractAccountUuid(AccountEntity account) {
        try {
            if (account == null || account.getExtra() == null || account.getExtra().isBlank()
                    || "{}".equals(account.getExtra().trim())) {
                return "";
            }
            JsonNode extra = JSON.readTree(account.getExtra());
            return extra.path(AnthropicClaudeCodeProfile.ACCOUNT_EXTRA_ACCOUNT_UUID).asText("").trim();
        } catch (Exception e) {
            log.debug("Failed to parse account_uuid for count_tokens OAuth normalization: account_id={}",
                    account != null ? account.getId() : null);
            return "";
        }
    }

    public record Result(
            String body,
            boolean mimicClaudeCode,
            FingerprintService.ClientFingerprint fingerprint
    ) {
    }
}
