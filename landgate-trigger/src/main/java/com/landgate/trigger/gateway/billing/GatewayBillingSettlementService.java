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
                    clientDisconnected,
                    requestId);
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

    /**
     * 记录成功请求但没有解析到上游用量的情况，不扣费、不累计额度。
     */
    public void recordNoUsageLog(String model,
                                 String platform,
                                 Long userId,
                                 Long apiKeyId,
                                 AccountEntity account,
                                 GroupEntity group,
                                 boolean stream,
                                 boolean clientDisconnected,
                                 long durationMs,
                                 HttpServletRequest request,
                                 String requestId,
                                 String reason) {
        try {
            UsageLogEntity logEntry = billingDomainService.recordNoUsageLog(
                    requestId, model, platform,
                    userId, apiKeyId,
                    account != null ? account.getId() : null,
                    group != null ? group.getId() : null,
                    group != null ? group.getRateMultiplier() : null,
                    stream, durationMs,
                    request != null ? request.getHeader("User-Agent") : null,
                    request != null ? request.getRemoteAddr() : null,
                    clientDisconnected,
                    reason);
            log.warn("[{}] 已记录 NO_USAGE 用量日志: log_id={}, account_id={}, model={}, reason={}",
                    requestId, logEntry.getId(), account != null ? account.getId() : null, model, reason);
        } catch (Exception e) {
            log.error("[{}] NO_USAGE 用量日志保存失败: user_id={}, model={}, reason={}",
                    requestId, userId, model, reason, e);
        }
    }
}
