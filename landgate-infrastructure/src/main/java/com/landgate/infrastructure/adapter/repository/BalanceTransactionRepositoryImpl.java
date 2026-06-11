package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.balance.adapter.repository.IBalanceTransactionRepository;
import com.landgate.domain.balance.model.entity.AdminBalanceTransactionEntity;
import com.landgate.domain.balance.model.entity.BalanceTransactionEntity;
import com.landgate.infrastructure.adapter.mapper.BalanceTransactionMapper;
import com.landgate.infrastructure.dao.IBalanceTransactionDao;
import com.landgate.infrastructure.dao.po.BalanceTransactionPO;
import com.landgate.types.enums.BalanceFundingType;
import com.landgate.types.enums.BalanceTransactionStatus;
import com.landgate.types.enums.BalanceTransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 余额流水仓储适配器实现 —— 通过 MyBatis DAO 持久化余额流水。
 */
@Component
@RequiredArgsConstructor
public class BalanceTransactionRepositoryImpl implements IBalanceTransactionRepository {

    private final IBalanceTransactionDao balanceTransactionDao;
    private final BalanceTransactionMapper balanceTransactionMapper;

    @Override
    public Optional<BalanceTransactionEntity> findById(Long id) {
        return Optional.ofNullable(balanceTransactionDao.selectById(id))
                .map(balanceTransactionMapper::toEntity);
    }

    @Override
    public Optional<BalanceTransactionEntity> findBySource(String sourceType, String sourceId,
                                                           BalanceTransactionType transactionType) {
        return Optional.ofNullable(balanceTransactionDao.selectBySource(sourceType, sourceId, transactionType.name()))
                .map(balanceTransactionMapper::toEntity);
    }

    @Override
    public BalanceTransactionEntity save(BalanceTransactionEntity entity) {
        BalanceTransactionPO po = balanceTransactionMapper.toPO(entity);
        if (po.getId() == null) {
            balanceTransactionDao.insert(po);
        } else {
            balanceTransactionDao.update(po);
        }
        return balanceTransactionMapper.toEntity(po);
    }

    @Override
    public void markPending(Long id) {
        balanceTransactionDao.markPending(id);
    }

    @Override
    public void markCompleted(Long id, BigDecimal balanceBefore, BigDecimal balanceAfter, Instant completedAt) {
        balanceTransactionDao.markCompleted(id, balanceBefore, balanceAfter, completedAt);
    }

    @Override
    public void markFailed(Long id, String failureReason) {
        balanceTransactionDao.markFailed(id, failureReason);
    }

    @Override
    public List<BalanceTransactionEntity> listByUserId(Long userId, int offset, int size) {
        return balanceTransactionDao.selectByUserId(userId, offset, size).stream()
                .map(balanceTransactionMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public long countByUserId(Long userId) {
        return balanceTransactionDao.countByUserId(userId);
    }

    @Override
    public List<AdminBalanceTransactionEntity> listAdmin(String keyword,
                                                         BalanceTransactionType transactionType,
                                                         BalanceFundingType fundingType,
                                                         BalanceTransactionStatus status,
                                                         int offset,
                                                         int size) {
        return balanceTransactionDao.selectAdmin(
                        normalizeKeyword(keyword),
                        transactionType != null ? transactionType.name() : null,
                        fundingType != null ? fundingType.name() : null,
                        status != null ? status.name() : null,
                        offset,
                        size
                ).stream()
                .map(balanceTransactionMapper::toAdminEntity)
                .collect(Collectors.toList());
    }

    @Override
    public long countAdmin(String keyword,
                           BalanceTransactionType transactionType,
                           BalanceFundingType fundingType,
                           BalanceTransactionStatus status) {
        return balanceTransactionDao.countAdmin(
                normalizeKeyword(keyword),
                transactionType != null ? transactionType.name() : null,
                fundingType != null ? fundingType.name() : null,
                status != null ? status.name() : null
        );
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        return keyword.trim();
    }
}
