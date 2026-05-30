package com.landgate.trigger.scheduler;

import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.billing.model.entity.UsageLogEntity;
import com.landgate.domain.billing.service.BillingDomainService;
import com.landgate.trigger.gateway.BalanceDomainService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计费对账调度器测试 —— 验证重试状态不会造成余额重复扣减。
 */
@DisplayName("BillingReconciliationScheduler 测试")
class BillingReconciliationSchedulerTest {

    @Test
    @DisplayName("余额扣减成功后 quota 更新失败时不重新标记为可自动重试的 FAILED")
    void quotaFailureAfterDeductionDoesNotMarkFailedForRetry() {
        BillingDomainService billingDomainService = mock(BillingDomainService.class);
        BalanceDomainService balanceDomainService = mock(BalanceDomainService.class);
        IUserRepository userRepository = mock(IUserRepository.class);
        BillingReconciliationScheduler scheduler = new BillingReconciliationScheduler(
                billingDomainService, balanceDomainService, userRepository);
        UsageLogEntity logEntry = UsageLogEntity.builder()
                .id(7L)
                .userId(3L)
                .apiKeyId(9L)
                .actualCost(new BigDecimal("0.10"))
                .billingStatus("FAILED")
                .build();
        when(billingDomainService.findBillingLogsByStatusBefore(eq("PENDING"), any(), eq(100)))
                .thenReturn(List.of());
        when(billingDomainService.findBillingLogsByStatusBefore(eq("FAILED"), any(), eq(100)))
                .thenReturn(List.of(logEntry));
        when(userRepository.findById(3L)).thenReturn(Optional.of(UserEntity.builder().id(3L).build()));
        doThrow(new IllegalStateException("quota db down"))
                .when(billingDomainService).accumulateQuota(9L, new BigDecimal("0.10"));

        scheduler.reconcile();

        verify(billingDomainService).markLogSettling(7L);
        verify(balanceDomainService).deduct(3L, new BigDecimal("0.10"));
        verify(billingDomainService, never()).markLogFailed(eq(7L), any());
        verify(billingDomainService, never()).markLogDeducted(7L);
    }
}
