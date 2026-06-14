package com.landgate.trigger.gateway.billing;

import com.landgate.domain.billing.model.valobj.UsageTokens;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Applies Sub2API-compatible force-cache-billing usage reclassification.
 *
 * <p>This service owns only the billing-side token category adjustment used
 * after a sticky session fails over. It is intentionally independent from
 * routing, protocol conversion, and upstream authentication.</p>
 */
@Slf4j
@Service
public class ForceCacheBillingUsageService {

    public void applyIfNeeded(UsageTokens usage, boolean forceCacheBilling, Long accountId, String requestId) {
        if (usage == null || !forceCacheBilling) {
            return;
        }
        if (usage.applyForceCacheBilling()) {
            log.debug("[{}] force_cache_billing applied: account_id={}, cache_read_tokens={}",
                    requestId, accountId, usage.getCacheReadTokens());
        }
    }
}
