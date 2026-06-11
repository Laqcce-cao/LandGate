package com.landgate.domain.balance.adapter.repository;

import com.landgate.domain.balance.model.entity.AdminBalanceTransactionEntity;
import com.landgate.domain.balance.model.entity.BalanceTransactionEntity;
import com.landgate.types.enums.BalanceFundingType;
import com.landgate.types.enums.BalanceTransactionStatus;
import com.landgate.types.enums.BalanceTransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 余额流水仓储接口 —— 定义余额变动记录的持久化契约。
 */
public interface IBalanceTransactionRepository {

    Optional<BalanceTransactionEntity> findById(Long id);

    Optional<BalanceTransactionEntity> findBySource(String sourceType, String sourceId,
                                                    BalanceTransactionType transactionType);

    BalanceTransactionEntity save(BalanceTransactionEntity entity);

    void markPending(Long id);

    void markCompleted(Long id, BigDecimal balanceBefore, BigDecimal balanceAfter, Instant completedAt);

    void markFailed(Long id, String failureReason);

    List<BalanceTransactionEntity> listByUserId(Long userId, int offset, int size);

    long countByUserId(Long userId);

    /**
     * 后台分页查询全站余额流水。
     */
    List<AdminBalanceTransactionEntity> listAdmin(String keyword,
                                                  BalanceTransactionType transactionType,
                                                  BalanceFundingType fundingType,
                                                  BalanceTransactionStatus status,
                                                  int offset,
                                                  int size);

    /**
     * 统计后台筛选条件下的余额流水数量。
     */
    long countAdmin(String keyword,
                    BalanceTransactionType transactionType,
                    BalanceFundingType fundingType,
                    BalanceTransactionStatus status);
}
