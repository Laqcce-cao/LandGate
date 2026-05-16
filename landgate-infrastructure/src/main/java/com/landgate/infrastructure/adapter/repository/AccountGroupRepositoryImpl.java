package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.group.adapter.repository.IAccountGroupRepository;
import com.landgate.domain.group.model.entity.AccountGroupEntity;
import com.landgate.infrastructure.adapter.mapper.AccountGroupMapper;
import com.landgate.infrastructure.dao.IAccountGroupDao;
import com.landgate.infrastructure.dao.po.AccountGroupPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 账号-分组关联仓储适配器实现 —— 实现 {@link IAccountGroupRepository} 接口。
 * <p>
 * 委托 {@link IAccountGroupDao} 进行数据访问，通过 {@link AccountGroupMapper} 完成 PO ↔ Entity 映射。
 * 使用复合主键，硬删除策略。
 */
@Component
@RequiredArgsConstructor
public class AccountGroupRepositoryImpl implements IAccountGroupRepository {

    private final IAccountGroupDao accountGroupDao;
    private final AccountGroupMapper accountGroupMapper;

    @Override
    public List<AccountGroupEntity> findByGroupId(Long groupId) {
        return accountGroupDao.selectByGroupId(groupId).stream()
                .map(accountGroupMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<AccountGroupEntity> findByGroupIdOrderByPriority(Long groupId) {
        return accountGroupDao.selectByGroupIdOrderByPriority(groupId).stream()
                .map(accountGroupMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<AccountGroupEntity> findByAccountId(Long accountId) {
        return accountGroupDao.selectByAccountId(accountId).stream()
                .map(accountGroupMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public AccountGroupEntity save(AccountGroupEntity entity) {
        AccountGroupPO po = accountGroupMapper.toPO(entity);
        accountGroupDao.insert(po);
        return accountGroupMapper.toEntity(po);
    }

    @Override
    public void deleteByGroupId(Long groupId) {
        accountGroupDao.deleteByGroupId(groupId);
    }

    @Override
    public void deleteByAccountId(Long accountId) {
        accountGroupDao.deleteByAccountId(accountId);
    }
}
