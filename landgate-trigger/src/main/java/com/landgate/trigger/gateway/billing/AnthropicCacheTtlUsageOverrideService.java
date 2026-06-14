package com.landgate.trigger.gateway.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.GatewayCacheTtlPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AnthropicCacheTtlUsageOverrideService {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${" + AnthropicClaudeCodeProfile.PROPERTY_CACHE_TTL_1H_INJECTION + ":false}")
    private boolean cacheTTL1hInjection;

    public record NonStreamingBodyResult(String body, boolean changed) {
    }

    public boolean applyIfNeeded(UsageTokens usage, AccountEntity account) {
        String target = resolveOverrideTarget(account, cacheTTL1hInjection);
        if (target == null || usage == null) {
            return false;
        }
        return usage.applyCacheTtlOverride(target);
    }

    public NonStreamingBodyResult applyToNonStreamingBody(String body, UsageTokens usage, AccountEntity account) {
        boolean changed = applyIfNeeded(usage, account);
        if (!changed || body == null || body.isBlank() || usage == null) {
            return new NonStreamingBodyResult(body, false);
        }
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isObject()) {
                return new NonStreamingBodyResult(body, false);
            }
            ObjectNode usageNode = objectChild((ObjectNode) root, AnthropicMessagesBodyPolicy.FIELD_USAGE);
            ObjectNode cacheCreation = objectChild(usageNode, AnthropicMessagesBodyPolicy.FIELD_CACHE_CREATION);
            cacheCreation.put(AnthropicMessagesBodyPolicy.FIELD_EPHEMERAL_5M_INPUT_TOKENS,
                    usage.getCacheCreation5mTokens());
            cacheCreation.put(AnthropicMessagesBodyPolicy.FIELD_EPHEMERAL_1H_INPUT_TOKENS,
                    usage.getCacheCreation1hTokens());
            return new NonStreamingBodyResult(JSON.writeValueAsString(root), true);
        } catch (Exception e) {
            log.debug("Failed to rewrite non-streaming cache TTL usage body: account_id={}",
                    account != null ? account.getId() : null);
            return new NonStreamingBodyResult(body, false);
        }
    }

    String resolveOverrideTarget(AccountEntity account, boolean globalCacheTTL1hInjection) {
        if (!isAnthropicOAuthOrSetupToken(account)) {
            return null;
        }
        String accountTarget = accountOverrideTarget(account);
        if (accountTarget != null) {
            return accountTarget;
        }
        return globalCacheTTL1hInjection ? GatewayCacheTtlPolicy.TARGET_5M : null;
    }

    private static boolean isAnthropicOAuthOrSetupToken(AccountEntity account) {
        return account != null
                && account.getPlatform() == Platform.ANTHROPIC
                && (account.getType() == AccountType.OAUTH
                || account.getType() == AccountType.SETUP_TOKEN);
    }

    private String accountOverrideTarget(AccountEntity account) {
        String extra = account == null ? null : account.getExtra();
        if (extra == null || extra.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(extra);
            if (!root.path(GatewayCacheTtlPolicy.EXTRA_CACHE_TTL_OVERRIDE_ENABLED).asBoolean(false)) {
                return null;
            }
            return GatewayCacheTtlPolicy.normalizeTarget(
                    root.path(GatewayCacheTtlPolicy.EXTRA_CACHE_TTL_OVERRIDE_TARGET).asText());
        } catch (Exception e) {
            log.debug("Invalid account cache TTL override extra ignored: account_id={}", account.getId());
            return null;
        }
    }

    private static ObjectNode objectChild(ObjectNode parent, String field) {
        JsonNode current = parent.path(field);
        if (current.isObject()) {
            return (ObjectNode) current;
        }
        ObjectNode child = JSON.createObjectNode();
        parent.set(field, child);
        return child;
    }
}
