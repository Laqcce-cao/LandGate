package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.UsageLogPO;
import com.landgate.infrastructure.dao.po.UserUsageSummaryPO;
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

    /** 查询所有日志（分页） */
    List<UsageLogPO> selectAll(@Param("offset") int offset, @Param("size") int size);

    /** 统计日志总数 */
    long countAll();

    /** 根据用户 ID 查询日志，按创建时间降序（分页） */
    List<UsageLogPO> selectByUserId(@Param("userId") Long userId, @Param("offset") int offset, @Param("size") int size);

    /** 根据 API Key ID 查询日志，按创建时间降序（分页） */
    List<UsageLogPO> selectByApiKeyId(@Param("apiKeyId") Long apiKeyId, @Param("offset") int offset, @Param("size") int size);

    /** 根据账号 ID 查询日志，按创建时间降序（分页） */
    List<UsageLogPO> selectByAccountId(@Param("accountId") Long accountId, @Param("offset") int offset, @Param("size") int size);

    /** 统计指定用户的日志数 */
    long countByUserId(@Param("userId") Long userId);

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
}
