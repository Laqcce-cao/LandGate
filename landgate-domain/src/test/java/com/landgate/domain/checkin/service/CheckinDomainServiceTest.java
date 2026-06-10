package com.landgate.domain.checkin.service;

import com.landgate.domain.balance.model.entity.BalanceTransactionEntity;
import com.landgate.domain.balance.model.valobj.BalanceTransactionCommand;
import com.landgate.domain.balance.service.BalanceTransactionDomainService;
import com.landgate.domain.checkin.adapter.repository.IUserCheckinRepository;
import com.landgate.domain.checkin.model.entity.UserCheckinEntity;
import com.landgate.types.enums.BalanceTransactionStatus;
import com.landgate.types.enums.CheckinStatus;
import com.landgate.types.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 签到领域服务测试 —— 验证签到幂等、连续天数和失败重试逻辑。
 */
@DisplayName("CheckinDomainService 测试")
class CheckinDomainServiceTest {

    private static final ZoneId CHECKIN_ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    @DisplayName("首次签到成功后记录 COMPLETED 并关联余额流水")
    void firstCheckinCompletes() {
        CapturingCheckinRepository repository = new CapturingCheckinRepository();
        BalanceTransactionDomainService balanceService = mockBalanceService();
        CheckinDomainService service = new CheckinDomainService(repository, balanceService);

        var result = service.checkin(7L);

        assertFalse(result.alreadySigned());
        assertEquals(CheckinStatus.COMPLETED.name(), result.record().getStatus().name());
        assertEquals(1, result.record().getStreakDays());
        assertEquals(new BigDecimal("0.05"), result.record().getRewardAmount());
        assertEquals(99L, result.record().getBalanceTransactionId());
        verify(balanceService).apply(any(BalanceTransactionCommand.class));
    }

    @Test
    @DisplayName("昨日已签到时连续天数累加")
    void streakIncrementsFromYesterday() {
        LocalDate today = LocalDate.now(CHECKIN_ZONE);
        CapturingCheckinRepository repository = new CapturingCheckinRepository(UserCheckinEntity.builder()
                .id(1L)
                .userId(7L)
                .signDate(today.minusDays(1))
                .streakDays(2)
                .rewardAmount(new BigDecimal("0.06"))
                .status(CheckinStatus.COMPLETED)
                .build());
        CheckinDomainService service = new CheckinDomainService(repository, mockBalanceService());

        var result = service.checkin(7L);

        assertEquals(3, result.record().getStreakDays());
        assertEquals(new BigDecimal("0.07"), result.record().getRewardAmount());
    }

    @Test
    @DisplayName("今日已完成签到时幂等返回且不重复发奖励")
    void completedTodayReturnsIdempotently() {
        LocalDate today = LocalDate.now(CHECKIN_ZONE);
        CapturingCheckinRepository repository = new CapturingCheckinRepository(UserCheckinEntity.builder()
                .id(1L)
                .userId(7L)
                .signDate(today)
                .streakDays(1)
                .rewardAmount(new BigDecimal("0.05"))
                .status(CheckinStatus.COMPLETED)
                .balanceTransactionId(99L)
                .build());
        BalanceTransactionDomainService balanceService = mockBalanceService();
        CheckinDomainService service = new CheckinDomainService(repository, balanceService);

        var result = service.checkin(7L);

        assertTrue(result.alreadySigned());
        verify(balanceService, never()).apply(any());
    }

    @Test
    @DisplayName("今日 PENDING 签到不自动重试")
    void pendingTodayThrows() {
        LocalDate today = LocalDate.now(CHECKIN_ZONE);
        CapturingCheckinRepository repository = new CapturingCheckinRepository(UserCheckinEntity.builder()
                .id(1L)
                .userId(7L)
                .signDate(today)
                .streakDays(1)
                .rewardAmount(new BigDecimal("0.05"))
                .status(CheckinStatus.PENDING)
                .build());
        CheckinDomainService service = new CheckinDomainService(repository, mockBalanceService());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.checkin(7L));

        assertEquals("CHECKIN_PENDING", ex.getErrorCode());
    }

    @Test
    @DisplayName("今日 FAILED 签到允许重试")
    void failedTodayRetries() {
        LocalDate today = LocalDate.now(CHECKIN_ZONE);
        CapturingCheckinRepository repository = new CapturingCheckinRepository(UserCheckinEntity.builder()
                .id(1L)
                .userId(7L)
                .signDate(today)
                .streakDays(1)
                .rewardAmount(new BigDecimal("0.05"))
                .status(CheckinStatus.FAILED)
                .failureReason("REDIS_ERROR")
                .build());
        BalanceTransactionDomainService balanceService = mockBalanceService();
        CheckinDomainService service = new CheckinDomainService(repository, balanceService);

        var result = service.checkin(7L);

        assertEquals(CheckinStatus.COMPLETED.name(), result.record().getStatus().name());
        assertNull(repository.saved.getFailureReason());
        verify(balanceService).apply(any());
    }

    @Test
    @DisplayName("状态接口：昨天已签到时今日可签并按下一连续天数计算奖励")
    void statusUsesYesterdayStreakForTodayReward() {
        LocalDate today = LocalDate.now(CHECKIN_ZONE);
        CapturingCheckinRepository repository = new CapturingCheckinRepository(UserCheckinEntity.builder()
                .id(1L)
                .userId(7L)
                .signDate(today.minusDays(1))
                .streakDays(2)
                .rewardAmount(new BigDecimal("0.06"))
                .status(CheckinStatus.COMPLETED)
                .build());
        CheckinDomainService service = new CheckinDomainService(repository, mockBalanceService());

        var status = service.getStatus(7L);

        assertFalse(status.signedToday());
        assertTrue(status.canCheckin());
        assertEquals("NONE", status.todayStatus());
        assertEquals(2, status.streakDays());
        assertEquals(new BigDecimal("0.07"), status.todayReward());
        assertEquals(new BigDecimal("0.08"), status.nextReward());
    }

    @Test
    @DisplayName("状态接口：断签后从第 1 天奖励重新开始")
    void statusResetsAfterMissedDay() {
        LocalDate today = LocalDate.now(CHECKIN_ZONE);
        CapturingCheckinRepository repository = new CapturingCheckinRepository(UserCheckinEntity.builder()
                .id(1L)
                .userId(7L)
                .signDate(today.minusDays(2))
                .streakDays(5)
                .rewardAmount(new BigDecimal("0.09"))
                .status(CheckinStatus.COMPLETED)
                .build());
        CheckinDomainService service = new CheckinDomainService(repository, mockBalanceService());

        var status = service.getStatus(7L);

        assertFalse(status.signedToday());
        assertTrue(status.canCheckin());
        assertEquals(0, status.streakDays());
        assertEquals(new BigDecimal("0.05"), status.todayReward());
        assertEquals(new BigDecimal("0.06"), status.nextReward());
    }

    @Test
    @DisplayName("状态接口：连续超过 7 天后奖励按第 7 天封顶")
    void statusCapsRewardsAfterSevenDays() {
        LocalDate today = LocalDate.now(CHECKIN_ZONE);
        CapturingCheckinRepository repository = new CapturingCheckinRepository(UserCheckinEntity.builder()
                .id(1L)
                .userId(7L)
                .signDate(today.minusDays(1))
                .streakDays(8)
                .rewardAmount(new BigDecimal("0.15"))
                .status(CheckinStatus.COMPLETED)
                .build());
        CheckinDomainService service = new CheckinDomainService(repository, mockBalanceService());

        var status = service.getStatus(7L);

        assertEquals(8, status.streakDays());
        assertEquals(new BigDecimal("0.15"), status.todayReward());
        assertEquals(new BigDecimal("0.15"), status.nextReward());
    }

    @Test
    @DisplayName("状态接口：今日 PENDING 禁止再次点击")
    void statusDisablesPendingToday() {
        LocalDate today = LocalDate.now(CHECKIN_ZONE);
        CapturingCheckinRepository repository = new CapturingCheckinRepository(UserCheckinEntity.builder()
                .id(1L)
                .userId(7L)
                .signDate(today)
                .streakDays(3)
                .rewardAmount(new BigDecimal("0.07"))
                .status(CheckinStatus.PENDING)
                .build());
        CheckinDomainService service = new CheckinDomainService(repository, mockBalanceService());

        var status = service.getStatus(7L);

        assertTrue(status.signedToday());
        assertFalse(status.canCheckin());
        assertEquals("PENDING", status.todayStatus());
        assertEquals(3, status.streakDays());
        assertEquals(new BigDecimal("0.07"), status.todayReward());
    }

    @Test
    @DisplayName("状态接口：今日 FAILED 允许重试")
    void statusAllowsRetryWhenFailedToday() {
        LocalDate today = LocalDate.now(CHECKIN_ZONE);
        CapturingCheckinRepository repository = new CapturingCheckinRepository(UserCheckinEntity.builder()
                .id(1L)
                .userId(7L)
                .signDate(today)
                .streakDays(3)
                .rewardAmount(new BigDecimal("0.07"))
                .status(CheckinStatus.FAILED)
                .build());
        CheckinDomainService service = new CheckinDomainService(repository, mockBalanceService());

        var status = service.getStatus(7L);

        assertTrue(status.signedToday());
        assertTrue(status.canCheckin());
        assertEquals("FAILED", status.todayStatus());
        assertEquals(new BigDecimal("0.07"), status.todayReward());
    }

    private BalanceTransactionDomainService mockBalanceService() {
        BalanceTransactionDomainService balanceService = mock(BalanceTransactionDomainService.class);
        when(balanceService.apply(any())).thenReturn(BalanceTransactionEntity.builder()
                .id(99L)
                .status(BalanceTransactionStatus.COMPLETED)
                .build());
        return balanceService;
    }

    private static class CapturingCheckinRepository implements IUserCheckinRepository {
        private final List<UserCheckinEntity> records = new ArrayList<>();
        private UserCheckinEntity saved;

        CapturingCheckinRepository(UserCheckinEntity... initial) {
            records.addAll(List.of(initial));
            if (initial.length > 0) saved = initial[initial.length - 1];
        }

        @Override
        public Optional<UserCheckinEntity> findById(Long id) {
            return records.stream().filter(r -> r.getId().equals(id)).findFirst();
        }

        @Override
        public Optional<UserCheckinEntity> findByUserIdAndSignDate(Long userId, LocalDate signDate) {
            return records.stream()
                    .filter(r -> r.getUserId().equals(userId))
                    .filter(r -> r.getSignDate().equals(signDate))
                    .findFirst();
        }

        @Override
        public Optional<UserCheckinEntity> findLatestByUserId(Long userId) {
            return records.stream()
                    .filter(r -> r.getUserId().equals(userId))
                    .max((a, b) -> a.getSignDate().compareTo(b.getSignDate()));
        }

        @Override
        public UserCheckinEntity save(UserCheckinEntity entity) {
            if (entity.getId() == null) entity.setId((long) records.size() + 1);
            records.add(entity);
            saved = entity;
            return entity;
        }

        @Override
        public void markPending(Long id) {
            findById(id).ifPresent(r -> {
                r.setStatus(CheckinStatus.PENDING);
                r.setFailureReason(null);
                saved = r;
            });
        }

        @Override
        public void markCompleted(Long id, Long balanceTransactionId) {
            findById(id).ifPresent(r -> {
                r.setStatus(CheckinStatus.COMPLETED);
                r.setBalanceTransactionId(balanceTransactionId);
                r.setFailureReason(null);
                saved = r;
            });
        }

        @Override
        public void markFailed(Long id, String failureReason) {
            findById(id).ifPresent(r -> {
                r.setStatus(CheckinStatus.FAILED);
                r.setFailureReason(failureReason);
                saved = r;
            });
        }

        @Override
        public List<UserCheckinEntity> listByUserId(Long userId, int offset, int size) {
            return records.stream().filter(r -> r.getUserId().equals(userId)).toList();
        }

        @Override
        public long countByUserId(Long userId) {
            return records.stream().filter(r -> r.getUserId().equals(userId)).count();
        }
    }
}
