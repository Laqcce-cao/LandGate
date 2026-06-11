package com.landgate.domain.checkin.adapter.repository;

import com.landgate.domain.checkin.model.entity.UserCheckinEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 用户签到仓储接口 —— 定义签到记录持久化契约。
 */
public interface IUserCheckinRepository {

    Optional<UserCheckinEntity> findById(Long id);

    Optional<UserCheckinEntity> findByUserIdAndSignDate(Long userId, LocalDate signDate);

    Optional<UserCheckinEntity> findLatestByUserId(Long userId);

    UserCheckinEntity save(UserCheckinEntity entity);

    void markPending(Long id);

    void markCompleted(Long id, Long balanceTransactionId);

    void markFailed(Long id, String failureReason);

    List<UserCheckinEntity> listByUserId(Long userId, int offset, int size);

    long countByUserId(Long userId);
}
