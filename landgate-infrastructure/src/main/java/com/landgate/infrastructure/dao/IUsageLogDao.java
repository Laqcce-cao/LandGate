package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 用量日志 DAO —— MyBatis Mapper 接口。
 * <p>
 * XML 映射文件：mapper/UsageLogMapper.xml
 * 对应表：usage_logs（无软删除，支持分页）
 */
@Mapper
public interface IUsageLogDao {

    /** 根据 ID 查询日志 */
    UsageLogPO selectById(@Param("id") Long id);

    /** 插入日志，useGeneratedKeys 回填 ID */
    int insert(UsageLogPO log);

    /** 更新日志计费状态 */
    int updateBillingStatus(@Param("id") Long id,
                            @Param("billingStatus") String billingStatus,
                            @Param("billingError") String billingError);

    /** 从可重试状态抢占日志进入扣费中，返回更新行数 */
    int updateBillingStatusFromPendingOrFailed(@Param("id") Long id,
                                               @Param("billingStatus") String billingStatus,
                                               @Param("billingError") String billingError);

    /** 查询指定计费状态且早于 cutoff 的日志 */
    List<UsageLogPO> selectByBillingStatusBefore(@Param("billingStatus") String billingStatus,
                                                 @Param("cutoff") Instant cutoff,
                                                 @Param("limit") int limit);

    /** 查询所有日志（分页） */
    List<UsageLogPO> selectAll(@Param("offset") int offset, @Param("size") int size);

    /** 统计日志总数 */
    long countAll();

    /** 按管理员筛选条件查询日志，按创建时间降序（分页），end 为排他边界 */
    List<UsageLogPO> selectByFilters(@Param("userId") Long userId,
                                     @Param("apiKeyId") Long apiKeyId,
                                     @Param("accountId") Long accountId,
                                     @Param("start") String start,
                                     @Param("end") String end,
                                     @Param("offset") int offset,
                                     @Param("size") int size);

    /** 统计管理员筛选条件下的日志数 */
    long countByFilters(@Param("userId") Long userId,
                        @Param("apiKeyId") Long apiKeyId,
                        @Param("accountId") Long accountId,
                        @Param("start") String start,
                        @Param("end") String end);

    /** 根据用户 ID 查询日志，按创建时间降序（分页） */
    List<UsageLogPO> selectByUserId(@Param("userId") Long userId, @Param("offset") int offset, @Param("size") int size);

    /** 根据用户 ID + 日期范围查询日志，按创建时间降序（分页），end 为排他边界 */
    List<UsageLogPO> selectByUserIdAndDate(@Param("userId") Long userId, @Param("offset") int offset,
                                           @Param("size") int size, @Param("start") String start,
                                           @Param("end") String end);

    /** 根据 API Key ID 查询日志，按创建时间降序（分页） */
    List<UsageLogPO> selectByApiKeyId(@Param("apiKeyId") Long apiKeyId, @Param("offset") int offset, @Param("size") int size);

    /** 根据账号 ID 查询日志，按创建时间降序（分页） */
    List<UsageLogPO> selectByAccountId(@Param("accountId") Long accountId, @Param("offset") int offset, @Param("size") int size);

    /** 统计指定用户的日志数 */
    long countByUserId(@Param("userId") Long userId);

    /** 统计指定用户在日期范围内的日志数，end 为排他边界 */
    long countByUserIdAndDate(@Param("userId") Long userId, @Param("start") String start,
                              @Param("end") String end);

    /** 统计指定 API Key 的日志数 */
    long countByApiKeyId(@Param("apiKeyId") Long apiKeyId);

    /** 统计指定账号的日志数 */
    long countByAccountId(@Param("accountId") Long accountId);

    /** 按用户聚合用量统计（支持时间范围 + 排序维度） */
    List<UserUsageSummaryPO> aggregateByUser(
            @Param("start") Instant start,
            @Param("end") Instant end,
            @Param("sortBy") String sortBy,
            @Param("sortDir") String sortDir
    );

    /**
     * 按天聚合指定用户的用量统计（支持日期范围）。
     * <p>
     * 用于前端 Token 用量趋势图表，后端直接返回按 DATE(created_at) 分组的结果，
     * 走 {@code idx_usage_logs_user_created (user_id, created_at)} 复合索引。
     *
     * @param userId 用户 ID
     * @param start  起始日期（包含，格式 yyyy-MM-dd）
     * @param end    结束日期（不包含，格式 yyyy-MM-dd）
     * @return 按天聚合的用量列表
     */
    List<DailyUsageStatsPO> aggregateByUserAndDate(
            @Param("userId") Long userId,
            @Param("start") String start,
            @Param("end") String end
    );

    // ---- 仪表盘聚合查询 ----

    long countByDateRange(@Param("start") Instant start, @Param("end") Instant end);

    double avgDurationByDateRange(@Param("start") Instant start, @Param("end") Instant end);

    TokenCostSummaryPO sumTokensAndCostByDateRange(@Param("start") Instant start, @Param("end") Instant end);

    List<PlatformDailyStatsPO> aggregateByDate(@Param("start") Instant start, @Param("end") Instant end);

    List<ModelStatsPO> aggregateByModel(@Param("start") Instant start, @Param("end") Instant end);

    List<UserDailyStatsPO> aggregateTopUsersByDate(@Param("start") Instant start, @Param("end") Instant end, @Param("topN") int topN);

    // ---- 用户仪表盘聚合查询 ----

    long countByUserIdAndDateRange(@Param("userId") Long userId, @Param("start") Instant start, @Param("end") Instant end);

    double avgDurationByUserIdAndDateRange(@Param("userId") Long userId, @Param("start") Instant start, @Param("end") Instant end);

    TokenCostSummaryPO sumTokensAndCostByUserIdAndDateRange(@Param("userId") Long userId, @Param("start") Instant start, @Param("end") Instant end);

    List<ModelStatsPO> aggregateByUserIdAndModel(@Param("userId") Long userId, @Param("start") Instant start, @Param("end") Instant end);
}
