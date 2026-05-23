package com.landgate.domain.group.adapter.repository;

import com.landgate.domain.group.model.entity.GroupEntity;

import java.util.List;
import java.util.Optional;

/**
 * 分组（Group）聚合仓储接口 —— 定义领域层所需的分组持久化操作契约。
 * <p>
 * 由基础设施层的 RepositoryImpl 实现，通过依赖反转实现领域层不依赖基础设施。
 * 管理用户/账户分组，支持按名称、平台、状态、订阅类型、是否专属等多维度查询，以及增删操作。
 */
public interface IGroupRepository {

    /**
     * 根据ID查询分组
     *
     * @param id 主键ID
     * @return 查询到的分组，不存在返回 Optional.empty()
     */
    Optional<GroupEntity> findById(Long id);

    /**
     * 根据名称查询分组
     *
     * @param name 分组名称
     * @return 查询到的分组，不存在返回 Optional.empty()
     */
    Optional<GroupEntity> findByName(String name);

    /**
     * 根据状态查询分组列表
     *
     * @param status 分组状态
     * @return 该状态下的分组列表
     */
    List<GroupEntity> findByStatus(String status);

    /**
     * 根据订阅类型查询分组列表
     *
     * @param subscriptionType 订阅类型
     * @return 匹配的分组列表
     */
    List<GroupEntity> findBySubscriptionType(String subscriptionType);

    /**
     * 查询所有专属分组
     *
     * @return 标记为专属的分组列表
     */
    List<GroupEntity> findByIsExclusiveTrue();

    /**
     * 查询所有分组
     *
     * @return 全部分组列表
     */
    List<GroupEntity> findAll();

    /**
     * 保存分组（新增或更新）
     *
     * @param entity 分组实体
     * @return 保存后的分组实体
     */
    GroupEntity save(GroupEntity entity);

    /**
     * 删除分组
     *
     * @param entity 待删除的分组实体
     */
    void delete(GroupEntity entity);
}
