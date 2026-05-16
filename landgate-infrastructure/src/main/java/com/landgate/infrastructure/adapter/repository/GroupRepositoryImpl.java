package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.group.adapter.repository.IGroupRepository;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.infrastructure.adapter.mapper.GroupMapper;
import com.landgate.infrastructure.dao.IGroupDao;
import com.landgate.infrastructure.dao.po.GroupPO;
import com.landgate.types.enums.Platform;
import com.landgate.types.enums.Status;
import com.landgate.types.enums.SubscriptionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 分组仓储适配器实现 —— 实现 {@link IGroupRepository} 接口。
 * <p>
 * 委托 {@link IGroupDao} 进行数据访问，通过 {@link GroupMapper} 完成 PO ↔ Entity 映射。
 * 使用软删除策略。
 */
@Component
@RequiredArgsConstructor
public class GroupRepositoryImpl implements IGroupRepository {

    private final IGroupDao groupDao;
    private final GroupMapper groupMapper;

    @Override
    public Optional<GroupEntity> findById(Long id) {
        return Optional.ofNullable(groupDao.selectById(id))
                .filter(po -> po.getDeletedAt() == null)
                .map(groupMapper::toEntity);
    }

    @Override
    public Optional<GroupEntity> findByName(String name) {
        return Optional.ofNullable(groupDao.selectByName(name))
                .map(groupMapper::toEntity);
    }

    @Override
    public List<GroupEntity> findByPlatform(String platform) {
        Platform p = Platform.valueOf(platform.toUpperCase());
        return groupDao.selectByPlatform(p.name()).stream()
                .map(groupMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<GroupEntity> findByStatus(String status) {
        Status s = Status.valueOf(status.toUpperCase());
        return groupDao.selectByStatus(s.name()).stream()
                .map(groupMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<GroupEntity> findBySubscriptionType(String subscriptionType) {
        SubscriptionType t = SubscriptionType.valueOf(subscriptionType.toUpperCase());
        return groupDao.selectBySubscriptionType(t.name()).stream()
                .map(groupMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<GroupEntity> findByIsExclusiveTrue() {
        return groupDao.selectExclusive().stream()
                .map(groupMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<GroupEntity> findAll() {
        return groupDao.selectAll().stream()
                .map(groupMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public GroupEntity save(GroupEntity entity) {
        GroupPO po = groupMapper.toPO(entity);
        if (po.getId() == null) {
            groupDao.insert(po);
        } else {
            groupDao.update(po);
        }
        return groupMapper.toEntity(po);
    }

    @Override
    public void delete(GroupEntity entity) {
        if (entity.getId() != null) {
            GroupPO po = groupDao.selectById(entity.getId());
            if (po != null) {
                po.setDeletedAt(Instant.now());
                groupDao.update(po);
            }
        }
    }
}
