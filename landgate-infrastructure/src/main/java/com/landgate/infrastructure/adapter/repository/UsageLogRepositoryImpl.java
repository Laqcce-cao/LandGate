package com.landgate.infrastructure.adapter.repository;

import com.landgate.api.billing.dto.*;
import com.landgate.domain.billing.adapter.repository.IUsageLogRepository;
import com.landgate.domain.billing.model.entity.UsageLogEntity;
import com.landgate.infrastructure.adapter.mapper.UsageLogMapper;
import com.landgate.infrastructure.dao.IUsageLogDao;
import com.landgate.infrastructure.dao.po.*;
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
    public List<UsageLogEntity> findByUserIdWithDate(Long userId, int page, int size,
                                                      LocalDate start, LocalDate end) {
        int offset = page * size;
        String startStr = start != null ? start.toString() : null;
        String endStr = end != null ? end.toString() : null;
        return usageLogDao.selectByUserIdAndDate(userId, offset, size, startStr, endStr).stream()
                .map(usageLogMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public long countByUserIdWithDate(Long userId, LocalDate start, LocalDate end) {
        String startStr = start != null ? start.toString() : null;
        String endStr = end != null ? end.toString() : null;
        return usageLogDao.countByUserIdAndDate(userId, startStr, endStr);
    }

    @Override
    public List<UsageLogEntity> findByFilters(Long userId, Long apiKeyId, Long accountId,
                                              LocalDate start, LocalDate end,
                                              int page, int size) {
        int offset = page * size;
        String startStr = start != null ? start.toString() : null;
        String endStr = end != null ? end.toString() : null;
        return usageLogDao.selectByFilters(userId, apiKeyId, accountId, startStr, endStr, offset, size).stream()
                .map(usageLogMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public long countByFilters(Long userId, Long apiKeyId, Long accountId, LocalDate start, LocalDate end) {
        String startStr = start != null ? start.toString() : null;
        String endStr = end != null ? end.toString() : null;
        return usageLogDao.countByFilters(userId, apiKeyId, accountId, startStr, endStr);
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
    public void updateBillingStatus(Long id, String billingStatus, String billingError) {
        usageLogDao.updateBillingStatus(id, billingStatus, billingError);
    }

    @Override
    public List<UsageLogEntity> findByBillingStatusBefore(String billingStatus, Instant cutoff, int limit) {
        return usageLogDao.selectByBillingStatusBefore(billingStatus, cutoff, limit).stream()
                .map(usageLogMapper::toEntity)
                .collect(Collectors.toList());
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

    @Override
    public long countByDateRange(Instant start, Instant end) {
        return usageLogDao.countByDateRange(start, end);
    }

    @Override
    public double avgDurationByDateRange(Instant start, Instant end) {
        return usageLogDao.avgDurationByDateRange(start, end);
    }

    @Override
    public TokenCostSummary sumTokensAndCostByDateRange(Instant start, Instant end) {
        TokenCostSummaryPO po = usageLogDao.sumTokensAndCostByDateRange(start, end);
        if (po == null) return new TokenCostSummary(0L, java.math.BigDecimal.ZERO);
        return new TokenCostSummary(po.getTotalTokens(), po.getTotalCost());
    }

    @Override
    public List<PlatformDailyStats> aggregatePlatformByDate(Instant start, Instant end) {
        return usageLogDao.aggregateByDate(start, end).stream()
                .map(po -> new PlatformDailyStats(
                        po.getDate(), po.getInputTokens(), po.getOutputTokens(),
                        po.getCacheReadTokens(), po.getCacheCreationTokens(), po.getCallCount()))
                .collect(Collectors.toList());
    }

    @Override
    public List<ModelStats> aggregateByModel(Instant start, Instant end) {
        return usageLogDao.aggregateByModel(start, end).stream()
                .map(po -> new ModelStats(po.getModel(), po.getTotalTokens(), po.getTotalCost(), po.getCallCount()))
                .collect(Collectors.toList());
    }

    @Override
    public List<UserDailyStats> aggregateTopUsersByDate(Instant start, Instant end, int topN) {
        return usageLogDao.aggregateTopUsersByDate(start, end, topN).stream()
                .map(po -> new UserDailyStats(po.getUserId(), po.getDate(), po.getTotalTokens()))
                .collect(Collectors.toList());
    }

    // ---- 用户仪表盘聚合查询 ----

    @Override
    public long countByUserIdAndDateRange(Long userId, Instant start, Instant end) {
        return usageLogDao.countByUserIdAndDateRange(userId, start, end);
    }

    @Override
    public double avgDurationByUserIdAndDateRange(Long userId, Instant start, Instant end) {
        return usageLogDao.avgDurationByUserIdAndDateRange(userId, start, end);
    }

    @Override
    public TokenCostSummary sumTokensAndCostByUserIdAndDateRange(Long userId, Instant start, Instant end) {
        TokenCostSummaryPO po = usageLogDao.sumTokensAndCostByUserIdAndDateRange(userId, start, end);
        if (po == null) return new TokenCostSummary(0L, java.math.BigDecimal.ZERO);
        return new TokenCostSummary(po.getTotalTokens(), po.getTotalCost());
    }

    @Override
    public List<ModelStats> aggregateByUserIdAndModel(Long userId, Instant start, Instant end) {
        return usageLogDao.aggregateByUserIdAndModel(userId, start, end).stream()
                .map(po -> new ModelStats(po.getModel(), po.getTotalTokens(), po.getTotalCost(), po.getCallCount()))
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
