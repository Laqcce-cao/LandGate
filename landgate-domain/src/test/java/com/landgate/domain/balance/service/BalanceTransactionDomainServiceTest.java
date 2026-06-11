package com.landgate.domain.balance.service;

import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.balance.adapter.repository.IBalanceTransactionRepository;
import com.landgate.domain.balance.adapter.runtime.IBalanceRuntime;
import com.landgate.domain.balance.model.entity.AdminBalanceTransactionEntity;
import com.landgate.domain.balance.model.entity.BalanceTransactionEntity;
import com.landgate.domain.balance.model.valobj.BalanceAdjustResult;
import com.landgate.domain.balance.model.valobj.BalanceTransactionCommand;
import com.landgate.types.enums.BalanceFundingType;
import com.landgate.types.enums.BalanceTransactionStatus;
import com.landgate.types.enums.BalanceTransactionType;
import com.landgate.types.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 余额流水领域服务测试 —— 验证幂等、失败重试和 Redis 未加载重试逻辑。
 */
@DisplayName("BalanceTransactionDomainService 测试")
class BalanceTransactionDomainServiceTest {

    @Test
    @DisplayName("新建流水后 Redis 调整成功，状态变为 COMPLETED")
    void applyCompletesWhenRedisAdjustSucceeds() {
        CapturingBalanceTransactionRepository repository = new CapturingBalanceTransactionRepository();
        StubBalanceRuntime runtime = new StubBalanceRuntime(BalanceAdjustResult.success(
                7L, new BigDecimal("10.00"), new BigDecimal("1.00"), new BigDecimal("11.00")));
        BalanceTransactionDomainService service = newService(repository, runtime);

        BalanceTransactionEntity tx = service.apply(command(new BigDecimal("10.00")));

        assertEquals(BalanceTransactionStatus.COMPLETED, tx.getStatus());
        assertEquals(new BigDecimal("1.00"), tx.getBalanceBefore());
        assertEquals(new BigDecimal("11.00"), tx.getBalanceAfter());
        assertNotNull(tx.getCompletedAt());
        assertEquals(1, runtime.adjustCalls);
        assertEquals(new BigDecimal("11.00"), runtime.flushedBalance);
    }

    @Test
    @DisplayName("Redis 未加载时从 MySQL load-if-absent 后重试")
    void applyLoadsBalanceIfRedisNotLoaded() {
        CapturingBalanceTransactionRepository repository = new CapturingBalanceTransactionRepository();
        StubBalanceRuntime runtime = new StubBalanceRuntime(
                BalanceAdjustResult.failure(7L, new BigDecimal("10.00"), "BALANCE_NOT_LOADED", "miss"),
                BalanceAdjustResult.success(7L, new BigDecimal("10.00"), new BigDecimal("5.00"), new BigDecimal("15.00"))
        );
        BalanceTransactionDomainService service = newService(repository, runtime);

        BalanceTransactionEntity tx = service.apply(command(new BigDecimal("10.00")));

        assertEquals(BalanceTransactionStatus.COMPLETED, tx.getStatus());
        assertEquals(2, runtime.adjustCalls);
        assertEquals(1, runtime.loadIfAbsentCalls);
        assertEquals(new BigDecimal("5.00"), runtime.loadedBalance);
    }

    @Test
    @DisplayName("余额不足时流水变为 FAILED")
    void applyMarksFailedWhenInsufficientBalance() {
        CapturingBalanceTransactionRepository repository = new CapturingBalanceTransactionRepository();
        StubBalanceRuntime runtime = new StubBalanceRuntime(BalanceAdjustResult.failure(
                7L, new BigDecimal("-10.00"), "INSUFFICIENT_BALANCE", "余额不足"));
        BalanceTransactionDomainService service = newService(repository, runtime);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.apply(command(new BigDecimal("-10.00"))));

        assertEquals("INSUFFICIENT_BALANCE", ex.getErrorCode());
        assertEquals(BalanceTransactionStatus.FAILED, repository.saved.getStatus());
        assertTrue(repository.saved.getFailureReason().contains("INSUFFICIENT_BALANCE"));
    }

    @Test
    @DisplayName("已完成流水重复调用直接返回，不重复调整 Redis")
    void applyReturnsCompletedTransactionIdempotently() {
        BalanceTransactionEntity existing = completedExisting();
        CapturingBalanceTransactionRepository repository = new CapturingBalanceTransactionRepository(existing);
        StubBalanceRuntime runtime = new StubBalanceRuntime(BalanceAdjustResult.success(
                7L, new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.TEN));
        BalanceTransactionDomainService service = newService(repository, runtime);

        BalanceTransactionEntity tx = service.apply(command(new BigDecimal("10.00")));

        assertSame(existing, tx);
        assertEquals(0, runtime.adjustCalls);
    }

    @Test
    @DisplayName("处理中流水不自动重试")
    void applyRejectsPendingTransaction() {
        BalanceTransactionEntity existing = existingWithStatus(BalanceTransactionStatus.PENDING);
        CapturingBalanceTransactionRepository repository = new CapturingBalanceTransactionRepository(existing);
        StubBalanceRuntime runtime = new StubBalanceRuntime(BalanceAdjustResult.success(
                7L, new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.TEN));
        BalanceTransactionDomainService service = newService(repository, runtime);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.apply(command(new BigDecimal("10.00"))));

        assertEquals("BALANCE_TRANSACTION_PENDING", ex.getErrorCode());
        assertEquals(0, runtime.adjustCalls);
    }

    @Test
    @DisplayName("失败流水允许重试")
    void applyRetriesFailedTransaction() {
        BalanceTransactionEntity existing = existingWithStatus(BalanceTransactionStatus.FAILED);
        existing.setFailureReason("REDIS_ERROR");
        CapturingBalanceTransactionRepository repository = new CapturingBalanceTransactionRepository(existing);
        StubBalanceRuntime runtime = new StubBalanceRuntime(BalanceAdjustResult.success(
                7L, new BigDecimal("10.00"), new BigDecimal("2.00"), new BigDecimal("12.00")));
        BalanceTransactionDomainService service = newService(repository, runtime);

        BalanceTransactionEntity tx = service.apply(command(new BigDecimal("10.00")));

        assertEquals(BalanceTransactionStatus.COMPLETED, tx.getStatus());
        assertNull(tx.getFailureReason());
        assertEquals(1, runtime.adjustCalls);
        assertTrue(repository.markPendingCalled);
    }

    private BalanceTransactionDomainService newService(CapturingBalanceTransactionRepository repository,
                                                       StubBalanceRuntime runtime) {
        return new BalanceTransactionDomainService(repository, runtime, new StubUserRepository());
    }

    private BalanceTransactionCommand command(BigDecimal amount) {
        return new BalanceTransactionCommand(
                7L,
                BalanceTransactionType.ADMIN_GRANT,
                BalanceFundingType.GIFT,
                amount,
                BigDecimal.ZERO,
                "ADMIN_OPERATION",
                "source-1",
                "ADMIN",
                "1",
                "测试余额调整",
                null,
                false
        );
    }

    private BalanceTransactionEntity completedExisting() {
        BalanceTransactionEntity entity = existingWithStatus(BalanceTransactionStatus.COMPLETED);
        entity.setBalanceBefore(BigDecimal.ONE);
        entity.setBalanceAfter(BigDecimal.TEN);
        entity.setCompletedAt(Instant.now());
        return entity;
    }

    private BalanceTransactionEntity existingWithStatus(BalanceTransactionStatus status) {
        return BalanceTransactionEntity.builder()
                .id(1L)
                .userId(7L)
                .transactionType(BalanceTransactionType.ADMIN_GRANT)
                .fundingType(BalanceFundingType.GIFT)
                .amount(new BigDecimal("10.00"))
                .cashIncomeAmount(BigDecimal.ZERO)
                .sourceType("ADMIN_OPERATION")
                .sourceId("source-1")
                .operatorType("ADMIN")
                .operatorId("1")
                .remark("测试余额调整")
                .status(status)
                .build();
    }

    private static class CapturingBalanceTransactionRepository implements IBalanceTransactionRepository {
        private BalanceTransactionEntity saved;
        private boolean markPendingCalled;

        CapturingBalanceTransactionRepository() {}

        CapturingBalanceTransactionRepository(BalanceTransactionEntity saved) {
            this.saved = saved;
        }

        @Override
        public Optional<BalanceTransactionEntity> findById(Long id) {
            return Optional.ofNullable(saved).filter(tx -> tx.getId().equals(id));
        }

        @Override
        public Optional<BalanceTransactionEntity> findBySource(String sourceType, String sourceId,
                                                               BalanceTransactionType transactionType) {
            return Optional.ofNullable(saved)
                    .filter(tx -> tx.getSourceType().equals(sourceType))
                    .filter(tx -> tx.getSourceId().equals(sourceId))
                    .filter(tx -> tx.getTransactionType() == transactionType);
        }

        @Override
        public BalanceTransactionEntity save(BalanceTransactionEntity entity) {
            if (entity.getId() == null) entity.setId(1L);
            saved = entity;
            return entity;
        }

        @Override
        public void markPending(Long id) {
            markPendingCalled = true;
            saved.setStatus(BalanceTransactionStatus.PENDING);
            saved.setFailureReason(null);
        }

        @Override
        public void markCompleted(Long id, BigDecimal balanceBefore, BigDecimal balanceAfter, Instant completedAt) {
            saved.setBalanceBefore(balanceBefore);
            saved.setBalanceAfter(balanceAfter);
            saved.setStatus(BalanceTransactionStatus.COMPLETED);
            saved.setFailureReason(null);
            saved.setCompletedAt(completedAt);
        }

        @Override
        public void markFailed(Long id, String failureReason) {
            saved.setStatus(BalanceTransactionStatus.FAILED);
            saved.setFailureReason(failureReason);
        }

        @Override
        public List<BalanceTransactionEntity> listByUserId(Long userId, int offset, int size) { return List.of(); }

        @Override
        public long countByUserId(Long userId) { return 0; }

        @Override
        public List<AdminBalanceTransactionEntity> listAdmin(String keyword,
                                                             BalanceTransactionType transactionType,
                                                             BalanceFundingType fundingType,
                                                             BalanceTransactionStatus status,
                                                             int offset,
                                                             int size) {
            return List.of();
        }

        @Override
        public long countAdmin(String keyword,
                               BalanceTransactionType transactionType,
                               BalanceFundingType fundingType,
                               BalanceTransactionStatus status) {
            return 0;
        }
    }

    private static class StubBalanceRuntime implements IBalanceRuntime {
        private final BalanceAdjustResult[] results;
        private int adjustCalls;
        private int loadIfAbsentCalls;
        private BigDecimal loadedBalance;
        private BigDecimal flushedBalance;

        StubBalanceRuntime(BalanceAdjustResult... results) {
            this.results = results;
        }

        @Override
        public BalanceAdjustResult adjustBalance(Long userId, BigDecimal amount, boolean allowNegative) {
            BalanceAdjustResult result = results[Math.min(adjustCalls, results.length - 1)];
            adjustCalls++;
            return result;
        }

        @Override
        public boolean loadBalanceIfAbsent(Long userId, BigDecimal balance) {
            loadIfAbsentCalls++;
            loadedBalance = balance;
            return true;
        }

        @Override
        public BigDecimal getBalance(Long userId) {
            BalanceAdjustResult last = results[Math.min(Math.max(adjustCalls - 1, 0), results.length - 1)];
            flushedBalance = last.balanceAfter();
            return flushedBalance;
        }
    }

    private static class StubUserRepository implements IUserRepository {
        private final UserEntity user = UserEntity.builder().id(7L).balance(new BigDecimal("5.00")).build();

        @Override public Optional<UserEntity> findByEmail(String email) { return Optional.empty(); }
        @Override public Optional<UserEntity> findById(Long id) { return Optional.of(user); }
        @Override public UserEntity save(UserEntity entity) { return entity; }
        @Override public boolean existsByEmail(String email) { return false; }
        @Override public long countByStatus(String status) { return 0; }
        @Override public long count() { return 0; }
        @Override public List<UserEntity> findBySearch(String search, int page, int pageSize) { return List.of(); }
        @Override public long countBySearch(String search) { return 0; }
        @Override public int updateBalance(Long id, BigDecimal newBalance) { return 1; }
        @Override public long countByCreatedAtAfter(Instant after) { return 0; }
    }
}
