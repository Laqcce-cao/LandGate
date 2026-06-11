package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.infrastructure.adapter.mapper.UserConverter;
import com.landgate.infrastructure.dao.IUserDao;
import com.landgate.infrastructure.dao.po.UserPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 用户仓储适配器实现 —— 实现 {@link IUserRepository} 接口。
 * <p>
 * 委托 {@link IUserDao} 进行数据访问，通过 {@link UserConverter} 完成 PO ↔ Entity 映射。
 */
@Component
@RequiredArgsConstructor
public class UserRepositoryImpl implements IUserRepository {

    private final IUserDao userDao;
    private final UserConverter userConverter;

    @Override
    public Optional<UserEntity> findByEmail(String email) {
        return Optional.ofNullable(userDao.selectByEmail(email))
                .map(userConverter::toEntity);
    }

    @Override
    public Optional<UserEntity> findById(Long id) {
        return Optional.ofNullable(userDao.selectById(id))
                .filter(po -> po.getDeletedAt() == null)
                .map(userConverter::toEntity);
    }

    @Override
    public UserEntity save(UserEntity entity) {
        UserPO po = userConverter.toPO(entity);
        if (po.getId() == null) {
            userDao.insert(po);
        } else {
            userDao.update(po);
        }
        return userConverter.toEntity(po);
    }

    @Override
    public boolean existsByEmail(String email) {
        return userDao.existsByEmail(email);
    }

    @Override
    public long countByStatus(String status) {
        return userDao.countByStatus(status);
    }

    @Override
    public long count() {
        return userDao.countAll();
    }

    @Override
    public java.util.List<UserEntity> findBySearch(String search, int page, int pageSize) {
        int offset = page * pageSize;
        return userDao.selectBySearch(search, offset, pageSize).stream()
                .map(userConverter::toEntity)
                .toList();
    }

    @Override
    public long countBySearch(String search) {
        return userDao.countBySearch(search);
    }

    @Override
    public int updateBalance(Long id, java.math.BigDecimal newBalance) {
        return userDao.updateBalance(id, newBalance);
    }

    @Override
    public long countByCreatedAtAfter(java.time.Instant after) {
        return userDao.countByCreatedAtAfter(after.toString());
    }
}
