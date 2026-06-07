package com.landgate.trigger.gateway.billing;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.billing.model.entity.UsageLogEntity;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.domain.billing.service.BillingDomainService;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.trigger.gateway.BalanceDomainService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Persists usage and settles user balance for a completed gateway request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayBillingSettlementService {

    private final BillingDomainService billingDomainService;
    private final BalanceDomainService balanceDomainService;

    /**
     * 保存用量日志并完成余额扣减，失败时保留可对账状态，避免资损静默发生。
     */
    public void settleUsageLog(UsageTokens usage,
                               String model,
                               String platform,
                               Long userId,
                               Long apiKeyId,
                               AccountEntity account,
                               GroupEntity group,
                               UserEntity user,
                               boolean stream,
                               boolean clientDisconnected,
                               long durationMs,
                               HttpServletRequest request,
                               String requestId) {
        UsageLogEntity logEntry;
        try {
            logEntry = billingDomainService.calculateAndBuildLog(
                    usage, model, platform,
                    userId, apiKeyId, account.getId(), group.getId(),
                    group.getRateMultiplier(),
                    stream, durationMs,
                    request.getHeader("User-Agent"),
                    request.getRemoteAddr(),
                    clientDisconnected);
        } catch (Exception e) {
            log.error("[{}] 用量日志保存失败，无法扣费: user_id={}, model={}, usage={}",
                    requestId, userId, model, usage, e);
            return;
        }

        boolean deducted = false;
        try {
            if (!user.isPrivileged()) {
                try {
                    billingDomainService.markLogSettling(logEntry.getId());
                    balanceDomainService.deduct(userId, logEntry.getActualCost());
                    deducted = true;
                    log.info("[{}] 余额扣减: user_id={}, cost={}", requestId, userId, logEntry.getActualCost());
                } catch (Exception e) {
                    log.error("[{}] 扣费失败，日志已保留待对账: log_id={}, user_id={}, cost={}",
                            requestId, logEntry.getId(), userId, logEntry.getActualCost(), e);
                    billingDomainService.markLogFailed(logEntry.getId(), e.getMessage());
                    return;
                }
            } else {
                log.info("[{}] 特权用户跳过扣费: user_id={}, cost={}", requestId, userId, logEntry.getActualCost());
            }

            billingDomainService.accumulateQuota(apiKeyId, logEntry.getActualCost());
            billingDomainService.markLogDeducted(logEntry.getId());
        } catch (Exception e) {
            log.error("[{}] 扣费后处理失败，日志保持 SETTLING 待人工对账: log_id={}, user_id={}, cost={}, deducted={}",
                    requestId, logEntry.getId(), userId, logEntry.getActualCost(), deducted, e);
            if (deducted) {
                billingDomainService.markLogSettlingFailed(logEntry.getId(), e.getMessage());
            } else {
                billingDomainService.markLogFailed(logEntry.getId(), e.getMessage());
            }
        }
    }
}
