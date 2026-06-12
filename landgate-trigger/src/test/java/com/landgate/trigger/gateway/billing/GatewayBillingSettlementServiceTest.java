package com.landgate.trigger.gateway.billing;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.billing.service.BillingDomainService;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.trigger.gateway.billing.BalanceDomainService;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
                mock(BalanceDomainService.class));
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
}
