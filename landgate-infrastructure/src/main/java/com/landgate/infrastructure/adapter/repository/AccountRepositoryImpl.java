package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.account.adapter.repository.IAccountRepository;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.infrastructure.adapter.mapper.AccountMapper;
import com.landgate.infrastructure.dao.IAccountDao;
import com.landgate.infrastructure.dao.po.AccountPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 账号仓储适配器实现 —— 实现 {@link IAccountRepository} 接口。
 * <p>
 * 委托 {@link IAccountDao} 进行数据访问，通过 {@link AccountMapper} 完成 PO ↔ Entity 映射。
 * 使用软删除策略（设置 deleted_at）。
 */
@Component
@RequiredArgsConstructor
public class AccountRepositoryImpl implements IAccountRepository {

    private final IAccountDao accountDao;
    private final AccountMapper accountMapper;

    @Override
    public List<AccountEntity> findByPlatform(String platform) {
        return accountDao.selectByPlatform(platform).stream()
                .map(accountMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<AccountEntity> findById(Long id) {
        return Optional.ofNullable(accountDao.selectById(id))
                .filter(po -> po.getDeletedAt() == null)
                .map(accountMapper::toEntity);
    }

    @Override
    public AccountEntity save(AccountEntity entity) {
        AccountPO po = accountMapper.toPO(entity);
        if (po.getId() == null) {
            accountDao.insert(po);
        } else {
            accountDao.update(po);
        }
        return accountMapper.toEntity(po);
    }

    @Override
    public void deleteById(Long id) {
        AccountPO po = accountDao.selectById(id);
        if (po != null) {
            po.setDeletedAt(Instant.now());
            accountDao.update(po);
        }
    }

    @Override
    public List<AccountEntity> findAll() {
        return accountDao.selectAll().stream()
                .map(accountMapper::toEntity)
                .collect(Collectors.toList());
    }
}
