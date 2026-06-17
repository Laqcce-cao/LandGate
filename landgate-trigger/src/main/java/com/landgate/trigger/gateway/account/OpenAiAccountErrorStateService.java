package com.landgate.trigger.gateway.account;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.gateway.OpenAiAccountAuthPolicy;
import com.landgate.types.gateway.OpenAiUpstreamErrorPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Applies OpenAI upstream error side effects to selected accounts.
 *
 * <p>The service owns account-state mutation orchestration only. Stable OpenAI
 * protocol facts live in {@link OpenAiUpstreamErrorPolicy}; Redis details live
 * behind {@link OpenAi403CounterStore}.</p>
 */
@Slf4j
@Component
public class OpenAiAccountErrorStateService {

    private final AccountSelector accountSelector;
    private final OpenAi403CounterStore openAi403CounterStore;

    public OpenAiAccountErrorStateService(AccountSelector accountSelector,
                                          OpenAi403CounterStore openAi403CounterStore) {
        this.accountSelector = accountSelector;
        this.openAi403CounterStore = openAi403CounterStore;
    }

    public void markPaymentRequired(AccountEntity account, String upstreamMessage) {
        markPaymentRequired(account, "", upstreamMessage);
    }

    public void markPaymentRequired(AccountEntity account, String upstreamBody, String upstreamMessage) {
        if (!isOpenAi(account) || accountSelector == null) {
            return;
        }
        accountSelector.markError(account.getId(),
                OpenAiUpstreamErrorPolicy.paymentRequiredAccountError(upstreamBody, upstreamMessage));
    }

    public void markPermanentUnauthorized(AccountEntity account, String upstreamBody, String upstreamMessage) {
        if (!isOpenAi(account) || accountSelector == null) {
            return;
        }
        String reason = OpenAiUpstreamErrorPolicy.permanentUnauthorizedAccountError(upstreamBody, upstreamMessage);
        if (!reason.isBlank()) {
            accountSelector.markError(account.getId(), reason);
        }
    }

    public void markOauthUnauthorized(AccountEntity account, String upstreamMessage) {
        if (!isOpenAi(account) || accountSelector == null) {
            return;
        }
        accountSelector.markTempUnschedulable(account.getId(),
                Instant.now().plusSeconds(OpenAiUpstreamErrorPolicy.OAUTH_UNAUTHORIZED_TEMP_UNSCHEDULABLE_SECONDS),
                OpenAiUpstreamErrorPolicy.oauthUnauthorizedTempUnschedulableReason(upstreamMessage));
    }

    public void markForbidden(AccountEntity account, String upstreamMessage) {
        if (!isOpenAi(account) || accountSelector == null) {
            return;
        }
        if (openAi403CounterStore == null) {
            accountSelector.markError(account.getId(),
                    OpenAiUpstreamErrorPolicy.forbiddenAccountError(upstreamMessage));
            return;
        }

        long count;
        try {
            count = openAi403CounterStore.increment(account.getId());
        } catch (RuntimeException e) {
            log.warn("OpenAI 403 counter increment failed; marking account error: account_id={}",
                    account.getId(), e);
            accountSelector.markError(account.getId(),
                    OpenAiUpstreamErrorPolicy.forbiddenAccountError(upstreamMessage));
            return;
        }

        if (count >= OpenAiUpstreamErrorPolicy.FORBIDDEN_DISABLE_THRESHOLD) {
            accountSelector.markError(account.getId(),
                    OpenAiUpstreamErrorPolicy.forbiddenThresholdAccountError(upstreamMessage, count));
            return;
        }

        accountSelector.markTempUnschedulable(account.getId(),
                Instant.now().plusSeconds(OpenAiUpstreamErrorPolicy.FORBIDDEN_TEMP_UNSCHEDULABLE_SECONDS),
                OpenAiUpstreamErrorPolicy.forbiddenTempUnschedulableReason(count, upstreamMessage));
    }

    public void resetForbiddenCounterAfterSuccess(AccountEntity account) {
        if (!isOpenAi(account) || openAi403CounterStore == null) {
            return;
        }
        try {
            openAi403CounterStore.reset(account.getId());
        } catch (RuntimeException e) {
            log.warn("OpenAI 403 counter reset failed: account_id={}", account.getId(), e);
        }
    }

    private static boolean isOpenAi(AccountEntity account) {
        return account != null
                && account.getId() != null
                && OpenAiAccountAuthPolicy.isOpenAiPlatform(account.getPlatform());
    }
}
