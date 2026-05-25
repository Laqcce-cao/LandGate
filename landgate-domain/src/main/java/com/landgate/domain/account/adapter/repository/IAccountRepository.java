package com.landgate.domain.account.adapter.repository;

import com.landgate.domain.account.model.entity.AccountEntity;

import java.util.List;
import java.util.Optional;

/**
 * 账户（Account）聚合仓储接口 —— 定义领域层所需的账户持久化操作契约。
 * <p>
 * 由基础设施层的 RepositoryImpl 实现，通过依赖反转实现领域层不依赖基础设施。
 * 提供按平台查询、按ID查找、保存、删除及全量查询等核心操作。
 */
public interface IAccountRepository {

    /**
     * 根据平台名称查询账户列表
     *
     * @param platform 平台标识（如 openai、azure 等）
     * @return 该平台下的所有账户
     */
    List<AccountEntity> findByPlatform(String platform);

    /**
     * 根据ID查询账户
     *
     * @param id 主键ID
     * @return 查询到的账户，不存在返回 Optional.empty()
     */
    Optional<AccountEntity> findById(Long id);

    /**
     * 批量根据ID查询账户
     *
     * @param ids 主键ID列表
     * @return 查询到的账户列表
     */
    List<AccountEntity> findByIds(List<Long> ids);

    /**
     * 保存账户（新增或更新）
     *
     * @param entity 账户实体
     * @return 保存后的账户实体（含自增ID等回填字段）
     */
    AccountEntity save(AccountEntity entity);

    /**
     * 根据ID删除账户
     *
     * @param id 主键ID
     */
    void deleteById(Long id);

    /**
     * 查询所有账户
     *
     * @return 全部账户列表
     */
    List<AccountEntity> findAll();
}
