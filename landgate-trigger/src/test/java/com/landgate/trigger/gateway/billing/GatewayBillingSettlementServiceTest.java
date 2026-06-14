package com.landgate.trigger.gateway.billing;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.billing.model.entity.UsageLogEntity;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.domain.billing.service.BillingDomainService;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.trigger.gateway.billing.BalanceDomainService;
import com.landgate.types.enums.Platform;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关计费结算服务测试 —— 验证无用量场景不会落库为 0 token 使用记录。
 */
@DisplayName("GatewayBillingSettlementService 测试")
class GatewayBillingSettlementServiceTest {

    @Test
    @DisplayName("无用量请求不记录 0 token 使用日志")
    void noUsageRequestDoesNotPersistZeroTokenUsageLog() {
        BillingDomainService billingDomainService = mock(BillingDomainService.class);
        GatewayBillingSettlementService service = new GatewayBillingSettlementService(
                billingDomainService,
                mock(BalanceDomainService.class),
                mock(AnthropicCacheTtlUsageOverrideService.class),
                mock(ForceCacheBillingUsageService.class));
        AccountEntity account = AccountEntity.builder()
                .id(2L)
                .platform(Platform.OPENAI)
                .build();
        GroupEntity group = GroupEntity.builder()
                .id(1L)
                .rateMultiplier(BigDecimal.ONE)
                .build();

        service.recordNoUsageLog("gpt-5.5", "OPENAI", 1L, 1L,
                account, group, true, false, 2845L, null,
                "req-no-usage", "usage_not_parsed");

        verify(billingDomainService, never()).recordNoUsageLog(
                anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong(),
                any(BigDecimal.class), anyBoolean(), anyLong(), isNull(), isNull(), anyBoolean(),
                eq("usage_not_parsed"));
    }

    @Test
    @DisplayName("force cache billing is applied before usage log calculation")
    void forceCacheBillingAppliedBeforeUsageLogCalculation() {
        BillingDomainService billingDomainService = mock(BillingDomainService.class);
        GatewayBillingSettlementService service = new GatewayBillingSettlementService(
                billingDomainService,
                mock(BalanceDomainService.class),
                mock(AnthropicCacheTtlUsageOverrideService.class),
                new ForceCacheBillingUsageService());
        AccountEntity account = AccountEntity.builder()
                .id(2L)
                .platform(Platform.OPENAI)
                .build();
        GroupEntity group = GroupEntity.builder()
                .id(1L)
                .rateMultiplier(BigDecimal.ONE)
                .build();
        UserEntity user = UserEntity.builder()
                .id(3L)
                .build();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(anyString())).thenReturn("test-client");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(billingDomainService.calculateAndBuildLog(
                any(UsageTokens.class), anyString(), anyString(), anyLong(), anyLong(),
                anyLong(), anyLong(), any(BigDecimal.class), anyBoolean(), anyLong(),
                anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(UsageLogEntity.builder()
                        .id(9L)
                        .actualCost(BigDecimal.ZERO)
                        .build());
        when(billingDomainService.tryMarkLogSettling(9L)).thenReturn(false);

        UsageTokens usage = UsageTokens.builder()
                .inputTokens(17)
                .cacheReadTokens(3)
                .outputTokens(11)
                .build();

        service.settleUsageLog(usage, "gpt-5.5", "OPENAI", 3L, 4L,
                account, group, user, false, false, 1200L, request,
                "req-force-cache", true);

        ArgumentCaptor<UsageTokens> captor = ArgumentCaptor.forClass(UsageTokens.class);
        verify(billingDomainService).calculateAndBuildLog(
                captor.capture(), eq("gpt-5.5"), eq("OPENAI"), eq(3L), eq(4L),
                eq(2L), eq(1L), eq(BigDecimal.ONE), eq(false), eq(1200L),
                eq("test-client"), eq("127.0.0.1"), eq(false), eq("req-force-cache"));
        assertEquals(0, captor.getValue().getInputTokens());
        assertEquals(20, captor.getValue().getCacheReadTokens());
        assertEquals(11, captor.getValue().getOutputTokens());
    }
}
