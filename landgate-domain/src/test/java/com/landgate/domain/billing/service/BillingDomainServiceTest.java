package com.landgate.domain.billing.service;

import com.landgate.domain.auth.adapter.repository.IApiKeyRepository;
import com.landgate.domain.billing.adapter.repository.IUsageLogRepository;
import com.landgate.domain.billing.model.entity.UsageLogEntity;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.domain.group.model.entity.GroupEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计费领域服务测试 —— 验证用量日志默认进入待扣费状态，便于后续对账。
 */
@DisplayName("BillingDomainService 测试")
class BillingDomainServiceTest {

    @Test
    @DisplayName("Token 用量日志保存时状态为 PENDING")
    void tokenUsageLogStartsPending() {
        ModelPricingDomainService pricingService = mock(ModelPricingDomainService.class);
        IUsageLogRepository usageLogRepository = new CapturingUsageLogRepository();
        BillingDomainService service = new BillingDomainService(pricingService, usageLogRepository, mock(IApiKeyRepository.class));
        when(pricingService.getInputPrice("gpt-5.5")).thenReturn(new BigDecimal("1"));
        when(pricingService.getOutputPrice("gpt-5.5")).thenReturn(new BigDecimal("2"));
        when(pricingService.getCacheWritePrice("gpt-5.5")).thenReturn(BigDecimal.ZERO);
        when(pricingService.getCacheReadPrice("gpt-5.5")).thenReturn(BigDecimal.ZERO);
        when(pricingService.supportsCacheBreakdown("gpt-5.5")).thenReturn(false);
        when(pricingService.getCacheWrite5mPrice("gpt-5.5")).thenReturn(BigDecimal.ZERO);
        when(pricingService.getCacheWrite1hPrice("gpt-5.5")).thenReturn(BigDecimal.ZERO);

        UsageLogEntity log = service.calculateAndBuildLog(UsageTokens.builder()
                        .inputTokens(100)
                        .outputTokens(50)
                        .build(),
                "gpt-5.5", "OPENAI", 3L, 6L, 16L, 1L,
                BigDecimal.ONE, true, 1200, "ua", "127.0.0.1");

        assertEquals("PENDING", log.getBillingStatus());
    }

    @Test
    @DisplayName("Token 用量日志保留网关 requestId")
    void tokenUsageLogKeepsGatewayRequestId() {
        ModelPricingDomainService pricingService = mock(ModelPricingDomainService.class);
        IUsageLogRepository usageLogRepository = new CapturingUsageLogRepository();
        BillingDomainService service = new BillingDomainService(pricingService, usageLogRepository, mock(IApiKeyRepository.class));
        when(pricingService.getInputPrice("gpt-5.5")).thenReturn(BigDecimal.ZERO);
        when(pricingService.getOutputPrice("gpt-5.5")).thenReturn(BigDecimal.ZERO);
        when(pricingService.getCacheWritePrice("gpt-5.5")).thenReturn(BigDecimal.ZERO);
        when(pricingService.getCacheReadPrice("gpt-5.5")).thenReturn(BigDecimal.ZERO);
        when(pricingService.supportsCacheBreakdown("gpt-5.5")).thenReturn(false);
        when(pricingService.getCacheWrite5mPrice("gpt-5.5")).thenReturn(BigDecimal.ZERO);
        when(pricingService.getCacheWrite1hPrice("gpt-5.5")).thenReturn(BigDecimal.ZERO);

        UsageLogEntity log = service.calculateAndBuildLog(UsageTokens.builder()
                        .inputTokens(1)
                        .build(),
                "gpt-5.5", "OPENAI", 3L, 6L, 16L, 1L,
                BigDecimal.ONE, false, 300, "ua", "127.0.0.1", false, "gateway-req-1");

        assertEquals("gateway-req-1", log.getRequestId());
    }

    @Test
    @DisplayName("成功但无用量时记录 NO_USAGE 日志")
    void noUsageLogIsRecordedWithoutBilling() {
        IUsageLogRepository usageLogRepository = new CapturingUsageLogRepository();
        BillingDomainService service = new BillingDomainService(mock(ModelPricingDomainService.class), usageLogRepository, mock(IApiKeyRepository.class));

        UsageLogEntity log = service.recordNoUsageLog("req-no-usage", "gpt-5.5", "OPENAI",
                3L, 6L, 16L, 1L, BigDecimal.ONE, true, 1200, "ua", "127.0.0.1",
                false, "usage_not_parsed");

        assertEquals("req-no-usage", log.getRequestId());
        assertEquals("NO_USAGE", log.getBillingStatus());
        assertEquals("usage_not_parsed", log.getBillingError());
        assertEquals(BigDecimal.ZERO.setScale(10), log.getActualCost());
    }

    @Test
    @DisplayName("图片用量日志保存倍率和 PENDING 状态")
    void imageUsageLogKeepsRateMultiplierAndStartsPending() {
        IUsageLogRepository usageLogRepository = new CapturingUsageLogRepository();
        BillingDomainService service = new BillingDomainService(mock(ModelPricingDomainService.class), usageLogRepository, mock(IApiKeyRepository.class));
        GroupEntity group = GroupEntity.builder()
                .id(1L)
                .imagePrice1k(new BigDecimal("0.04"))
                .build();

        var log = service.calculateImageCost("gpt-image", "1K", 2,
                group, new BigDecimal("1.25"), 3L, 6L, 16L,
                "req-1", false, 800, "ua", "127.0.0.1");

        assertEquals("PENDING", log.getBillingStatus());
        assertEquals(new BigDecimal("1.2500"), log.getRateMultiplier());
    }

    @Test
    @DisplayName("markLogDeducted 将日志标记为已扣费")
    void markLogDeductedUpdatesStatus() {
        IUsageLogRepository usageLogRepository = mock(IUsageLogRepository.class);
        BillingDomainService service = new BillingDomainService(mock(ModelPricingDomainService.class), usageLogRepository, mock(IApiKeyRepository.class));

        service.markLogDeducted(9L);

        verify(usageLogRepository).updateBillingStatus(9L, "DEDUCTED", null);
    }

    @Test
    @DisplayName("tryMarkLogSettling 仅通过条件更新抢占可扣费日志")
    void tryMarkLogSettlingUsesConditionalUpdate() {
        IUsageLogRepository usageLogRepository = mock(IUsageLogRepository.class);
        BillingDomainService service = new BillingDomainService(mock(ModelPricingDomainService.class), usageLogRepository, mock(IApiKeyRepository.class));
        when(usageLogRepository.updateBillingStatusFromPendingOrFailed(9L, "SETTLING", null)).thenReturn(true);

        boolean claimed = service.tryMarkLogSettling(9L);

        assertEquals(true, claimed);
        verify(usageLogRepository).updateBillingStatusFromPendingOrFailed(9L, "SETTLING", null);
    }

    private static class CapturingUsageLogRepository implements IUsageLogRepository {
        @Override
        public UsageLogEntity save(UsageLogEntity entity) {
            entity.setId(1L);
            return entity;
        }

        @Override public Optional<UsageLogEntity> findById(Long id) { return Optional.empty(); }
        @Override public List<UsageLogEntity> findByUserId(Long userId, int page, int size) { return List.of(); }
        @Override public List<UsageLogEntity> findByUserIdWithDate(Long userId, int page, int size, LocalDate start, LocalDate end) { return List.of(); }
        @Override public long countByUserIdWithDate(Long userId, LocalDate start, LocalDate end) { return 0; }
        @Override public List<UsageLogEntity> findByFilters(Long userId, Long apiKeyId, Long accountId, LocalDate start, LocalDate end, int page, int size) { return List.of(); }
        @Override public long countByFilters(Long userId, Long apiKeyId, Long accountId, LocalDate start, LocalDate end) { return 0; }
        @Override public List<UsageLogEntity> findByApiKeyId(Long apiKeyId, int page, int size) { return List.of(); }
        @Override public List<UsageLogEntity> findByAccountId(Long accountId, int page, int size) { return List.of(); }
        @Override public long countByUserId(Long userId) { return 0; }
        @Override public long countByApiKeyId(Long apiKeyId) { return 0; }
        @Override public long countByAccountId(Long accountId) { return 0; }
        @Override public void updateBillingStatus(Long id, String billingStatus, String billingError) {}
        @Override public boolean updateBillingStatusFromPendingOrFailed(Long id, String billingStatus, String billingError) { return true; }
        @Override public List<UsageLogEntity> findByBillingStatusBefore(String billingStatus, Instant cutoff, int limit) { return List.of(); }
        @Override public List<UsageLogEntity> findAll(int page, int size) { return List.of(); }
        @Override public long count() { return 0; }
        @Override public List<com.landgate.api.billing.dto.UserUsageSummary> aggregateUsageByUser(Instant start, Instant end, String sortBy, String sortDir) { return List.of(); }
        @Override public List<com.landgate.api.billing.dto.DailyUsageStats> aggregateByUserAndDate(Long userId, LocalDate start, LocalDate end) { return List.of(); }
        @Override public long countByDateRange(Instant start, Instant end) { return 0; }
        @Override public double avgDurationByDateRange(Instant start, Instant end) { return 0; }
        @Override public com.landgate.api.billing.dto.TokenCostSummary sumTokensAndCostByDateRange(Instant start, Instant end) { return null; }
        @Override public List<com.landgate.api.billing.dto.PlatformDailyStats> aggregatePlatformByDate(Instant start, Instant end) { return List.of(); }
        @Override public List<com.landgate.api.billing.dto.ModelStats> aggregateByModel(Instant start, Instant end) { return List.of(); }
        @Override public List<com.landgate.api.billing.dto.UserDailyStats> aggregateTopUsersByDate(Instant start, Instant end, int topN) { return List.of(); }
        @Override public long countByUserIdAndDateRange(Long userId, Instant start, Instant end) { return 0; }
        @Override public double avgDurationByUserIdAndDateRange(Long userId, Instant start, Instant end) { return 0; }
        @Override public com.landgate.api.billing.dto.TokenCostSummary sumTokensAndCostByUserIdAndDateRange(Long userId, Instant start, Instant end) { return null; }
        @Override public List<com.landgate.api.billing.dto.ModelStats> aggregateByUserIdAndModel(Long userId, Instant start, Instant end) { return List.of(); }
    }
}
