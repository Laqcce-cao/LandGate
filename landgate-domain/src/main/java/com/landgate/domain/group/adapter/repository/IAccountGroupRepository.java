package com.landgate.domain.group.adapter.repository;

import com.landgate.domain.group.model.entity.AccountGroupEntity;

import java.util.List;

/**
 * 账户-分组关联（AccountGroup）仓储接口 —— 定义领域层所需的账户与分组多对多关联持久化操作契约。
 * <p>
 * 由基础设施层的 RepositoryImpl 实现，通过依赖反转实现领域层不依赖基础设施。
 * 管理账户在不同分组中的映射关系及优先级，支持按分组、账户维度查询和批量删除。
 */
public interface IAccountGroupRepository {

    /**
     * 查询指定分组下的所有账户关联
     *
     * @param groupId 分组ID
     * @return 该分组下的账户关联列表
     */
    List<AccountGroupEntity> findByGroupId(Long groupId);

    /**
     * 按优先级排序查询指定分组下的账户关联
     *
     * @param groupId 分组ID
     * @return 按优先级升序排列的账户关联列表
     */
    List<AccountGroupEntity> findByGroupIdOrderByPriority(Long groupId);

    /**
     * 查询指定账户所属的所有分组关联
     *
     * @param accountId 账户ID
     * @return 该账户的分组关联列表
     */
    List<AccountGroupEntity> findByAccountId(Long accountId);

    /**
     * 保存账户-分组关联
     *
     * @param entity 关联实体
     * @return 保存后的关联实体
     */
    AccountGroupEntity save(AccountGroupEntity entity);

    /**
     * 删除指定分组下的所有账户关联
     *
     * @param groupId 分组ID
     */
    void deleteByGroupId(Long groupId);

    /**
     * 删除指定账户的所有分组关联
     *
     * @param accountId 账户ID
     */
    void deleteByAccountId(Long accountId);
}
