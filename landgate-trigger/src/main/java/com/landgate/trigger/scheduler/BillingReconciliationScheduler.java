package com.landgate.trigger.scheduler;

import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.billing.model.entity.UsageLogEntity;
import com.landgate.domain.billing.service.BillingDomainService;
import com.landgate.trigger.gateway.BalanceDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 计费对账调度器 —— 重试已记录但未完成扣费的用量日志。
 * <p>
 * usage_logs 是扣费事实来源；当 Redis 或数据库短暂故障导致扣费失败时，
 * 通过该任务重新执行扣费并更新状态，避免资损静默发生。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingReconciliationScheduler {

    private static final int BATCH_SIZE = 100;
    private static final long GRACE_SECONDS = 120;

    private final BillingDomainService billingDomainService;
    private final BalanceDomainService balanceDomainService;
    private final IUserRepository userRepository;

    @Scheduled(fixedDelay = 60_000)
    public void reconcile() {
        Instant cutoff = Instant.now().minusSeconds(GRACE_SECONDS);
        reconcileStatus("PENDING", cutoff);
        reconcileStatus("FAILED", cutoff);
    }

    private void reconcileStatus(String status, Instant cutoff) {
        List<UsageLogEntity> logs = billingDomainService.findBillingLogsByStatusBefore(status, cutoff, BATCH_SIZE);
        if (logs.isEmpty()) return;

        int success = 0;
        int failed = 0;
        for (UsageLogEntity logEntry : logs) {
            boolean deducted = false;
            try {
                if (!billingDomainService.tryMarkLogSettling(logEntry.getId())) {
                    log.info("Billing reconciliation skipped claimed log: log_id={}, status={}",
                            logEntry.getId(), logEntry.getBillingStatus());
                    continue;
                }

                boolean privileged = userRepository.findById(logEntry.getUserId())
                        .map(user -> user.isPrivileged())
                        .orElse(false);
                try {
                    if (!privileged) {
                        balanceDomainService.deduct(logEntry.getUserId(), logEntry.getActualCost());
                        deducted = true;
                    }
                } catch (Exception e) {
                    billingDomainService.markLogFailed(logEntry.getId(), e.getMessage());
                    throw e;
                }

                // 余额扣减成功后进入 SETTLING；后续 quota/status 失败不能再标记 FAILED 自动重试，避免重复扣费。
                billingDomainService.accumulateQuota(logEntry.getApiKeyId(), logEntry.getActualCost());
                billingDomainService.markLogDeducted(logEntry.getId());
                success++;
            } catch (Exception e) {
                failed++;
                if (!deducted) {
                    billingDomainService.markLogFailed(logEntry.getId(), e.getMessage());
                } else {
                    billingDomainService.markLogSettlingFailed(logEntry.getId(), e.getMessage());
                }
                log.error("Billing reconciliation failed: log_id={}, user_id={}, cost={}, deducted={}",
                        logEntry.getId(), logEntry.getUserId(), logEntry.getActualCost(), deducted, e);
            }
        }

        log.info("Billing reconciliation status={} finished: success={}, failed={}", status, success, failed);
    }
}
