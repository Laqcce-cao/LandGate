package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.billing.adapter.repository.IUsageLogRepository;
import com.landgate.domain.billing.model.entity.UsageLogEntity;
import com.landgate.infrastructure.adapter.mapper.UsageLogMapper;
import com.landgate.infrastructure.dao.IUsageLogDao;
import com.landgate.infrastructure.dao.po.UsageLogPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 用量日志仓储适配器实现 —— 实现 {@link IUsageLogRepository} 接口。
 * <p>
 * 委托 {@link IUsageLogDao} 进行数据访问，通过 {@link UsageLogMapper} 完成 PO ↔ Entity 映射。
 * 用量日志不继承 BasePO，不使用软删除。
 */
@Component
@RequiredArgsConstructor
public class UsageLogRepositoryImpl implements IUsageLogRepository {

    private final IUsageLogDao usageLogDao;
    private final UsageLogMapper usageLogMapper;

    @Override
    public Optional<UsageLogEntity> findById(Long id) {
        return Optional.ofNullable(usageLogDao.selectById(id))
                .map(usageLogMapper::toEntity);
    }

    @Override
    public List<UsageLogEntity> findByUserId(Long userId, int page, int size) {
        int offset = page * size;
        return usageLogDao.selectByUserId(userId, offset, size).stream()
                .map(usageLogMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<UsageLogEntity> findByApiKeyId(Long apiKeyId, int page, int size) {
        int offset = page * size;
        return usageLogDao.selectByApiKeyId(apiKeyId, offset, size).stream()
                .map(usageLogMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<UsageLogEntity> findByAccountId(Long accountId, int page, int size) {
        int offset = page * size;
        return usageLogDao.selectByAccountId(accountId, offset, size).stream()
                .map(usageLogMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public long countByUserId(Long userId) {
        return usageLogDao.countByUserId(userId);
    }

    @Override
    public long countByApiKeyId(Long apiKeyId) {
        return usageLogDao.countByApiKeyId(apiKeyId);
    }

    @Override
    public long countByAccountId(Long accountId) {
        return usageLogDao.countByAccountId(accountId);
    }

    @Override
    public UsageLogEntity save(UsageLogEntity entity) {
        UsageLogPO po = usageLogMapper.toPO(entity);
        if (po.getId() == null) {
            usageLogDao.insert(po);
        } else {
            // UsageLog has no update (immutable log)
            usageLogDao.insert(po);
        }
        return usageLogMapper.toEntity(po);
    }

    @Override
    public List<UsageLogEntity> findAll(int page, int size) {
        int offset = page * size;
        return usageLogDao.selectAll(offset, size).stream()
                .map(usageLogMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public long count() {
        return usageLogDao.countAll();
    }
}
