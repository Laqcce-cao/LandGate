package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.checkin.adapter.repository.IUserCheckinRepository;
import com.landgate.domain.checkin.model.entity.UserCheckinEntity;
import com.landgate.infrastructure.adapter.mapper.UserCheckinMapper;
import com.landgate.infrastructure.dao.IUserCheckinDao;
import com.landgate.infrastructure.dao.po.UserCheckinPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 用户签到仓储适配器实现 —— 通过 MyBatis DAO 持久化签到记录。
 */
@Component
@RequiredArgsConstructor
public class UserCheckinRepositoryImpl implements IUserCheckinRepository {

    private final IUserCheckinDao userCheckinDao;
    private final UserCheckinMapper userCheckinMapper;

    @Override
    public Optional<UserCheckinEntity> findById(Long id) {
        return Optional.ofNullable(userCheckinDao.selectById(id)).map(userCheckinMapper::toEntity);
    }

    @Override
    public Optional<UserCheckinEntity> findByUserIdAndSignDate(Long userId, LocalDate signDate) {
        return Optional.ofNullable(userCheckinDao.selectByUserIdAndSignDate(userId, signDate))
                .map(userCheckinMapper::toEntity);
    }

    @Override
    public Optional<UserCheckinEntity> findLatestByUserId(Long userId) {
        return Optional.ofNullable(userCheckinDao.selectLatestByUserId(userId)).map(userCheckinMapper::toEntity);
    }

    @Override
    public UserCheckinEntity save(UserCheckinEntity entity) {
        UserCheckinPO po = userCheckinMapper.toPO(entity);
        if (po.getId() == null) {
            userCheckinDao.insert(po);
        } else {
            userCheckinDao.update(po);
        }
        return userCheckinMapper.toEntity(po);
    }

    @Override
    public void markPending(Long id) {
        userCheckinDao.markPending(id);
    }

    @Override
    public void markCompleted(Long id, Long balanceTransactionId) {
        userCheckinDao.markCompleted(id, balanceTransactionId);
    }

    @Override
    public void markFailed(Long id, String failureReason) {
        userCheckinDao.markFailed(id, failureReason);
    }

    @Override
    public List<UserCheckinEntity> listByUserId(Long userId, int offset, int size) {
        return userCheckinDao.selectByUserId(userId, offset, size).stream()
                .map(userCheckinMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public long countByUserId(Long userId) {
        return userCheckinDao.countByUserId(userId);
    }
}
