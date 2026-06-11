package com.landgate.trigger.gateway.billing;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.auth.adapter.port.IEmailPort;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("NoUsageAlertService 测试")
class NoUsageAlertServiceTest {

    @Test
    @DisplayName("同一账户达到阈值时发送一次告警，冷却期内不重复发送")
    void sendsAlertWhenAccountCrossesThreshold() {
        IEmailPort emailPort = mock(IEmailPort.class);
        NoUsageAlertService service = new NoUsageAlertService(
                emailPort,
                true,
                "ops@example.com",
                2,
                300,
                1800);
        AccountEntity account = AccountEntity.builder()
                .id(2L)
                .name("openai-oauth")
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .build();
        GroupEntity group = GroupEntity.builder()
                .id(1L)
                .rateMultiplier(BigDecimal.ONE)
                .build();

        service.onNoUsage("gpt-5.5", "OPENAI", 10L, 20L, account, group,
                true, false, 100L, null, "req-1", "usage_not_parsed");
        verify(emailPort, never()).sendAlertEmail(anyString(), anyString(), anyString());

        service.onNoUsage("gpt-5.5", "OPENAI", 10L, 20L, account, group,
                true, false, 120L, null, "req-2", "usage_not_parsed");
        verify(emailPort, times(1)).sendAlertEmail(anyString(), anyString(), anyString());

        service.onNoUsage("gpt-5.5", "OPENAI", 10L, 20L, account, group,
                true, false, 140L, null, "req-3", "usage_not_parsed");
        verify(emailPort, times(1)).sendAlertEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("未开启或未配置收件人时不发送告警")
    void disabledAlertDoesNotSendEmail() {
        IEmailPort emailPort = mock(IEmailPort.class);
        NoUsageAlertService service = new NoUsageAlertService(
                emailPort,
                false,
                "ops@example.com",
                1,
                300,
                1800);
        AccountEntity account = AccountEntity.builder().id(2L).build();

        service.onNoUsage("gpt-5.5", "OPENAI", 10L, 20L, account, null,
                true, false, 100L, null, "req-1", "usage_not_parsed");

        verify(emailPort, never()).sendAlertEmail(anyString(), anyString(), anyString());
    }
}
