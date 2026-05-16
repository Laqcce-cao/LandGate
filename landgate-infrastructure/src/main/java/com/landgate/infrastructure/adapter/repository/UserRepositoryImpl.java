package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.infrastructure.adapter.mapper.UserMapper;
import com.landgate.infrastructure.dao.IUserDao;
import com.landgate.infrastructure.dao.po.UserPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 用户仓储适配器实现 —— 实现 {@link IUserRepository} 接口。
 * <p>
 * 委托 {@link IUserDao} 进行数据访问，通过 {@link UserMapper} 完成 PO ↔ Entity 映射。
 */
@Component
@RequiredArgsConstructor
public class UserRepositoryImpl implements IUserRepository {

    private final IUserDao userDao;
    private final UserMapper userMapper;

    @Override
    public Optional<UserEntity> findByEmail(String email) {
        return Optional.ofNullable(userDao.selectByEmail(email))
                .map(userMapper::toEntity);
    }

    @Override
    public Optional<UserEntity> findById(Long id) {
        return Optional.ofNullable(userDao.selectById(id))
                .filter(po -> po.getDeletedAt() == null)
                .map(userMapper::toEntity);
    }

    @Override
    public UserEntity save(UserEntity entity) {
        UserPO po = userMapper.toPO(entity);
        if (po.getId() == null) {
            userDao.insert(po);
        } else {
            userDao.update(po);
        }
        return userMapper.toEntity(po);
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
    public int updateBalance(Long id, java.math.BigDecimal newBalance) {
        return userDao.updateBalance(id, newBalance);
    }
}
