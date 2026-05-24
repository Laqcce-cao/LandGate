package com.landgate.domain.billing.adapter.repository;

import com.landgate.api.billing.dto.DailyUsageStats;
import com.landgate.api.billing.dto.UserUsageSummary;
import com.landgate.domain.billing.model.entity.UsageLogEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 使用日志（UsageLog）仓储接口 —— 定义领域层所需的用量日志持久化操作契约。
 * <p>
 * 由基础设施层的 RepositoryImpl 实现，通过依赖反转实现领域层不依赖基础设施。
 * 记录每次API调用的用量详情，支持按用户、API密钥、账户等维度分页查询和统计。
 */
public interface IUsageLogRepository {

    /**
     * 根据ID查询使用日志
     *
     * @param id 主键ID
     * @return 查询到的日志实体，不存在返回 Optional.empty()
     */
    Optional<UsageLogEntity> findById(Long id);

    /**
     * 分页查询指定用户的用量日志
     *
     * @param userId 用户ID
     * @param page   页码（从0开始）
     * @param size   每页数量
     * @return 该用户的用量日志列表
     */
    List<UsageLogEntity> findByUserId(Long userId, int page, int size);

    /**
     * 分页查询指定用户在日期范围内的用量日志。
     *
     * @param userId 用户ID
     * @param page   页码（从0开始）
     * @param size   每页数量
     * @param start  起始日期（包含），null 表示不限
     * @param end    结束日期（不包含），null 表示不限
     * @return 该用户的用量日志列表
     */
    List<UsageLogEntity> findByUserIdWithDate(Long userId, int page, int size, LocalDate start, LocalDate end);
    long countByUserIdWithDate(Long userId, LocalDate start, LocalDate end);

    /**
     * 分页查询指定API密钥的用量日志
     *
     * @param apiKeyId API密钥ID
     * @param page     页码（从0开始）
     * @param size     每页数量
     * @return 该密钥的用量日志列表
     */
    List<UsageLogEntity> findByApiKeyId(Long apiKeyId, int page, int size);

    /**
     * 分页查询指定账户的用量日志
     *
     * @param accountId 账户ID
     * @param page      页码（从0开始）
     * @param size      每页数量
     * @return 该账户的用量日志列表
     */
    List<UsageLogEntity> findByAccountId(Long accountId, int page, int size);

    /**
     * 统计指定用户的用量日志总数
     *
     * @param userId 用户ID
     * @return 日志总数
     */
    long countByUserId(Long userId);

    /**
     * 统计指定API密钥的用量日志总数
     *
     * @param apiKeyId API密钥ID
     * @return 日志总数
     */
    long countByApiKeyId(Long apiKeyId);

    /**
     * 统计指定账户的用量日志总数
     *
     * @param accountId 账户ID
     * @return 日志总数
     */
    long countByAccountId(Long accountId);

    /**
     * 保存使用日志
     *
     * @param entity 日志实体
     * @return 保存后的日志实体
     */
    UsageLogEntity save(UsageLogEntity entity);

    /**
     * 分页查询所有使用日志
     *
     * @param page 页码（从0开始）
     * @param size 每页数量
     * @return 当前页的日志列表
     */
    List<UsageLogEntity> findAll(int page, int size);

    /**
     * 统计使用日志总数量
     *
     * @return 日志总数
     */
    long count();

    /**
     * 按用户聚合用量统计（指定时间窗口，按指定维度排序）。
     *
     * @param start   时间窗口起始（包含）
     * @param end     时间窗口结束（不包含）
     * @param sortBy  排序字段（totalCost / totalTokens）
     * @param sortDir 排序方向（ASC / DESC）
     * @return 用户用量汇总列表
     */
    List<UserUsageSummary> aggregateUsageByUser(Instant start, Instant end, String sortBy, String sortDir);

    /**
     * 按天聚合指定用户的用量统计（指定日期范围）。
     * <p>
     * 用于前端 Token 用量趋势图表，后端直接返回按天分组的结果，
     * 避免前端拉取大量原始日志后二次聚合。
     *
     * @param userId 用户 ID
     * @param start  起始日期（包含）
     * @param end    结束日期（不包含）
     * @return 按天聚合的用量统计列表
     */
    List<DailyUsageStats> aggregateByUserAndDate(Long userId, LocalDate start, LocalDate end);
}
