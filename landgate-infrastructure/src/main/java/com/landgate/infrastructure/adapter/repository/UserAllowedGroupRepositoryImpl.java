package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.group.adapter.repository.IUserAllowedGroupRepository;
import com.landgate.domain.group.model.entity.UserAllowedGroupEntity;
import com.landgate.infrastructure.adapter.mapper.UserAllowedGroupMapper;
import com.landgate.infrastructure.dao.IUserAllowedGroupDao;
import com.landgate.infrastructure.dao.po.UserAllowedGroupPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户-分组授权仓储适配器实现 —— 实现 {@link IUserAllowedGroupRepository} 接口。
 * <p>
 * 委托 {@link IUserAllowedGroupDao} 进行数据访问，通过 {@link UserAllowedGroupMapper} 完成 PO ↔ Entity 映射。
 * 使用复合主键，硬删除策略。
 */
@Component
@RequiredArgsConstructor
public class UserAllowedGroupRepositoryImpl implements IUserAllowedGroupRepository {

    private final IUserAllowedGroupDao userAllowedGroupDao;
    private final UserAllowedGroupMapper userAllowedGroupMapper;

    @Override
    public List<UserAllowedGroupEntity> findByUserId(Long userId) {
        return userAllowedGroupDao.selectByUserId(userId).stream()
                .map(userAllowedGroupMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<UserAllowedGroupEntity> findByGroupId(Long groupId) {
        return userAllowedGroupDao.selectByGroupId(groupId).stream()
                .map(userAllowedGroupMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public UserAllowedGroupEntity save(UserAllowedGroupEntity entity) {
        UserAllowedGroupPO po = userAllowedGroupMapper.toPO(entity);
        userAllowedGroupDao.insert(po);
        return userAllowedGroupMapper.toEntity(po);
    }

    @Override
    public void deleteByUserId(Long userId) {
        userAllowedGroupDao.deleteByUserId(userId);
    }

    @Override
    public void deleteByGroupId(Long groupId) {
        userAllowedGroupDao.deleteByGroupId(groupId);
    }
}
