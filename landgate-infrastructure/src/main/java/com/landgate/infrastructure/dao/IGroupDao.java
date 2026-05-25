package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.GroupPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 分组 DAO —— MyBatis Mapper 接口。
 * <p>
 * XML 映射文件：mapper/GroupMapper.xml
 * 对应表：groups（软删除）
 */
@Mapper
public interface IGroupDao {

    /** 根据 ID 查询未删除的分组 */
    GroupPO selectById(@Param("id") Long id);

    /** 插入分组，useGeneratedKeys 回填 ID */
    int insert(GroupPO group);

    /** 更新分组所有字段 */
    int update(GroupPO group);

    /** 查询所有未删除的分组 */
    List<GroupPO> selectAll();

    /** 统计未删除分组总数 */
    long countAll();

    /** 根据名称查询未删除的分组 */
    GroupPO selectByName(@Param("name") String name);

    /** 根据状态查询未删除的分组 */
    List<GroupPO> selectByStatus(@Param("status") String status);

    /** 根据订阅类型查询未删除的分组 */
    List<GroupPO> selectBySubscriptionType(@Param("subscriptionType") String subscriptionType);

    /** 查询所有独占且未删除的分组 */
    List<GroupPO> selectExclusive();
}
