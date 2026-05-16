package com.landgate.domain.auth.adapter.repository;

import com.landgate.domain.auth.model.entity.UserEntity;

import java.util.Optional;

/**
 * 用户（User）聚合仓储接口 —— 定义领域层所需的用户持久化操作契约。
 * <p>
 * 由基础设施层的 RepositoryImpl 实现，通过依赖反转实现领域层不依赖基础设施。
 * 提供用户注册、邮箱查重、余额更新、状态统计等核心操作。
 */
public interface IUserRepository {

    /**
     * 根据邮箱查询用户
     *
     * @param email 用户邮箱
     * @return 查询到的用户，不存在返回 Optional.empty()
     */
    Optional<UserEntity> findByEmail(String email);

    /**
     * 根据ID查询用户
     *
     * @param id 主键ID
     * @return 查询到的用户，不存在返回 Optional.empty()
     */
    Optional<UserEntity> findById(Long id);

    /**
     * 保存用户（新增或更新）
     *
     * @param entity 用户实体
     * @return 保存后的用户实体
     */
    UserEntity save(UserEntity entity);

    /**
     * 检查邮箱是否已注册
     *
     * @param email 用户邮箱
     * @return true 表示邮箱已被占用
     */
    boolean existsByEmail(String email);

    /**
     * 按状态统计用户数量
     *
     * @param status 用户状态（如 active、disabled）
     * @return 该状态下的用户总数
     */
    long countByStatus(String status);

    /**
     * 统计用户总数量
     *
     * @return 用户总数
     */
    long count();

    /**
     * 更新用户余额
     *
     * @param id         用户ID
     * @param newBalance 新的余额值
     * @return 受影响的行数
     */
    int updateBalance(Long id, java.math.BigDecimal newBalance);
}
