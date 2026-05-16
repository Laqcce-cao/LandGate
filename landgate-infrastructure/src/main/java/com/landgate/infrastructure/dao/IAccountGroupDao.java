package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.AccountGroupPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 账号-分组关联 DAO —— MyBatis Mapper 接口。
 * <p>
 * XML 映射文件：mapper/AccountGroupMapper.xml
 * 对应表：account_groups（复合主键，硬删除）
 */
@Mapper
public interface IAccountGroupDao {

    /** 根据分组 ID 查询关联记录 */
    List<AccountGroupPO> selectByGroupId(@Param("groupId") Long groupId);

    /** 根据分组 ID 查询关联记录，按优先级升序排列 */
    List<AccountGroupPO> selectByGroupIdOrderByPriority(@Param("groupId") Long groupId);

    /** 根据账号 ID 查询关联记录 */
    List<AccountGroupPO> selectByAccountId(@Param("accountId") Long accountId);

    /** 插入关联记录 */
    int insert(AccountGroupPO accountGroup);

    /** 更新关联记录 */
    int update(AccountGroupPO accountGroup);

    /** 根据分组 ID 硬删除所有关联 */
    void deleteByGroupId(@Param("groupId") Long groupId);

    /** 根据账号 ID 硬删除所有关联 */
    void deleteByAccountId(@Param("accountId") Long accountId);
}
