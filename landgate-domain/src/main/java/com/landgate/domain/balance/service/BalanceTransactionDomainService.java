package com.landgate.domain.balance.service;

import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.balance.adapter.repository.IBalanceTransactionRepository;
import com.landgate.domain.balance.adapter.runtime.IBalanceRuntime;
import com.landgate.domain.balance.model.entity.BalanceTransactionEntity;
import com.landgate.domain.balance.model.valobj.BalanceAdjustResult;
import com.landgate.domain.balance.model.valobj.BalanceTransactionCommand;
import com.landgate.types.enums.BalanceTransactionStatus;
import com.landgate.types.exception.BusinessException;
import com.landgate.types.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 余额流水领域服务 —— 统一处理低频余额变动、幂等、Redis 运行态调整和流水状态流转。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceTransactionDomainService {

    private final IBalanceTransactionRepository transactionRepository;
    private final IBalanceRuntime balanceRuntime;
    private final IUserRepository userRepository;

    /**
     * 应用一笔低频余额变动。
     * <p>
     * 幂等键为 source_type + source_id + transaction_type：已完成直接返回，失败允许重试，处理中不自动重试。
     */
    public BalanceTransactionEntity apply(BalanceTransactionCommand command) {
        validate(command);

        var existing = transactionRepository
                .findBySource(command.sourceType(), command.sourceId(), command.transactionType())
                .orElse(null);
        if (existing != null) {
            if (existing.getStatus() == BalanceTransactionStatus.COMPLETED) {
                return existing;
            }
            if (existing.getStatus() == BalanceTransactionStatus.PENDING) {
                throw new BusinessException("BALANCE_TRANSACTION_PENDING", "余额变动正在处理中，请稍后重试");
            }
            transactionRepository.markPending(existing.getId());
            existing.setStatus(BalanceTransactionStatus.PENDING);
            existing.setFailureReason(null);
            return executeAdjustment(existing, command);
        }

        BalanceTransactionEntity tx = BalanceTransactionEntity.builder()
                .userId(command.userId())
                .transactionType(command.transactionType())
                .fundingType(command.fundingType())
                .amount(command.amount())
                .cashIncomeAmount(command.cashIncomeAmount() != null ? command.cashIncomeAmount() : BigDecimal.ZERO)
                .sourceType(command.sourceType())
                .sourceId(command.sourceId())
                .operatorType(command.operatorType())
                .operatorId(command.operatorId())
                .remark(command.remark())
                .metadata(command.metadata())
                .status(BalanceTransactionStatus.PENDING)
                .build();
        tx = transactionRepository.save(tx);
        return executeAdjustment(tx, command);
    }

    private BalanceTransactionEntity executeAdjustment(BalanceTransactionEntity tx, BalanceTransactionCommand command) {
        BalanceAdjustResult result = balanceRuntime.adjustBalance(command.userId(), command.amount(), command.allowNegative());
        if (!result.success() && "BALANCE_NOT_LOADED".equals(result.errorCode())) {
            UserEntity user = userRepository.findById(command.userId())
                    .orElseThrow(() -> new NotFoundException("User not found: " + command.userId()));
            balanceRuntime.loadBalanceIfAbsent(command.userId(), user.getBalance());
            result = balanceRuntime.adjustBalance(command.userId(), command.amount(), command.allowNegative());
        }

        if (!result.success()) {
            String reason = result.errorCode() + (result.errorMessage() != null ? ": " + result.errorMessage() : "");
            transactionRepository.markFailed(tx.getId(), reason);
            tx.setStatus(BalanceTransactionStatus.FAILED);
            tx.setFailureReason(reason);
            throw new BusinessException(result.errorCode(), reason);
        }

        Instant completedAt = Instant.now();
        transactionRepository.markCompleted(tx.getId(), result.balanceBefore(), result.balanceAfter(), completedAt);
        tx.setBalanceBefore(result.balanceBefore());
        tx.setBalanceAfter(result.balanceAfter());
        tx.setStatus(BalanceTransactionStatus.COMPLETED);
        tx.setCompletedAt(completedAt);
        tx.setFailureReason(null);

        flushCurrentRedisBalance(command.userId());
        return tx;
    }

    /**
     * 分页查询用户自己的余额明细。
     */
    public java.util.List<BalanceTransactionEntity> listUserTransactions(Long userId, int page, int size) {
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        return transactionRepository.listByUserId(userId, normalizedPage * normalizedSize, normalizedSize);
    }

    /**
     * 统计用户自己的余额明细数量。
     */
    public long countUserTransactions(Long userId) {
        return transactionRepository.countByUserId(userId);
    }

    private void flushCurrentRedisBalance(Long userId) {
        try {
            BigDecimal currentBalance = balanceRuntime.getBalance(userId);
            if (currentBalance != null) {
                userRepository.updateBalance(userId, currentBalance);
            }
        } catch (Exception e) {
            log.warn("Immediate balance flush failed, scheduler will retry: user_id={}", userId, e);
        }
    }

    private void validate(BalanceTransactionCommand command) {
        if (command.userId() == null || command.userId() <= 0) {
            throw new BusinessException("INVALID_USER", "用户 ID 无效");
        }
        if (command.transactionType() == null) {
            throw new BusinessException("INVALID_TRANSACTION_TYPE", "余额变动类型不能为空");
        }
        if (command.fundingType() == null) {
            throw new BusinessException("INVALID_FUNDING_TYPE", "资金性质不能为空");
        }
        if (command.amount() == null || command.amount().compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessException("INVALID_AMOUNT", "余额变动金额不能为 0");
        }
        if (command.sourceType() == null || command.sourceType().isBlank()) {
            throw new BusinessException("INVALID_SOURCE", "来源类型不能为空");
        }
        if (command.sourceId() == null || command.sourceId().isBlank()) {
            throw new BusinessException("INVALID_SOURCE", "来源 ID 不能为空");
        }
        if (command.operatorType() == null || command.operatorType().isBlank()) {
            throw new BusinessException("INVALID_OPERATOR", "操作人类型不能为空");
        }
    }
}
