package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.ApiKeyPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * API Key DAO —— MyBatis Mapper 接口。
 * <p>
 * XML 映射文件：mapper/ApiKeyMapper.xml
 * 对应表：api_keys（软删除）
 */
@Mapper
public interface IApiKeyDao {

    /** 根据 ID 查询未删除的 Key */
    ApiKeyPO selectById(@Param("id") Long id);

    /** 插入 Key，useGeneratedKeys 回填 ID */
    int insert(ApiKeyPO apiKey);

    /** 更新 Key 所有字段 */
    int update(ApiKeyPO apiKey);

    /** 查询所有未删除的 Key */
    List<ApiKeyPO> selectAll();

    /** 统计未删除 Key 总数 */
    long countAll();

    /** 根据 Key 值查询未删除的 Key */
    ApiKeyPO selectByKey(@Param("key") String key);

    /** 根据用户 ID 查询未删除的 Key 列表 */
    List<ApiKeyPO> selectByUserId(@Param("userId") Long userId);

    /** 根据分组 ID 查询未删除的 Key 列表 */
    List<ApiKeyPO> selectByGroupId(@Param("groupId") Long groupId);

    /** 按状态统计未删除 Key 数 */
    long countByStatus(@Param("status") String status);
}
