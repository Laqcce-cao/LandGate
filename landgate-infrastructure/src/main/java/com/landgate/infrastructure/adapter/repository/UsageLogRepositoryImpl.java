package com.landgate.infrastructure.adapter.repository;

import com.landgate.api.billing.dto.DailyUsageStats;
import com.landgate.api.billing.dto.UserUsageSummary;
import com.landgate.domain.billing.adapter.repository.IUsageLogRepository;
import com.landgate.domain.billing.model.entity.UsageLogEntity;
import com.landgate.infrastructure.adapter.mapper.UsageLogMapper;
import com.landgate.infrastructure.dao.IUsageLogDao;
import com.landgate.infrastructure.dao.po.DailyUsageStatsPO;
import com.landgate.infrastructure.dao.po.UsageLogPO;
import com.landgate.infrastructure.dao.po.UserUsageSummaryPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
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

    @Override
    public List<UserUsageSummary> aggregateUsageByUser(Instant start, Instant end, String sortBy, String sortDir) {
        return usageLogDao.aggregateByUser(start, end, sortBy, sortDir).stream()
                .map(this::toUserUsageSummary)
                .collect(Collectors.toList());
    }

    @Override
    public List<DailyUsageStats> aggregateByUserAndDate(Long userId, LocalDate start, LocalDate end) {
        return usageLogDao.aggregateByUserAndDate(userId, start.toString(), end.toString()).stream()
                .map(this::toDailyUsageStats)
                .collect(Collectors.toList());
    }

    private UserUsageSummary toUserUsageSummary(UserUsageSummaryPO po) {
        return new UserUsageSummary(
                po.getUserId(),
                po.getUsername(),
                po.getEmail(),
                po.getTotalCost(),
                po.getTotalTokens(),
                po.getCallCount()
        );
    }

    /**
     * 将按天聚合的 PO 转换为领域 DTO。
     */
    private DailyUsageStats toDailyUsageStats(DailyUsageStatsPO po) {
        return new DailyUsageStats(
                po.getDate(),
                po.getInputTokens(),
                po.getOutputTokens(),
                po.getCacheReadTokens(),
                po.getCallCount()
        );
    }
}
