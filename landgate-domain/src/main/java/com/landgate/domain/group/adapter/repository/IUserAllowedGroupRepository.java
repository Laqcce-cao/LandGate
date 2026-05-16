package com.landgate.domain.group.adapter.repository;

import com.landgate.domain.group.model.entity.UserAllowedGroupEntity;

import java.util.List;

/**
 * 用户-允许分组（UserAllowedGroup）仓储接口 —— 定义领域层所需的用户可访问分组白名单持久化操作契约。
 * <p>
 * 由基础设施层的 RepositoryImpl 实现，通过依赖反转实现领域层不依赖基础设施。
 * 控制哪些用户有权限访问特定分组，支持按用户和分组维度查询及批量删除。
 */
public interface IUserAllowedGroupRepository {

    /**
     * 查询指定用户被允许访问的所有分组
     *
     * @param userId 用户ID
     * @return 该用户可访问的分组关联列表
     */
    List<UserAllowedGroupEntity> findByUserId(Long userId);

    /**
     * 查询允许访问指定分组的所有用户
     *
     * @param groupId 分组ID
     * @return 可访问该分组的用户关联列表
     */
    List<UserAllowedGroupEntity> findByGroupId(Long groupId);

    /**
     * 保存用户-分组访问权限
     *
     * @param entity 权限实体
     * @return 保存后的权限实体
     */
    UserAllowedGroupEntity save(UserAllowedGroupEntity entity);

    /**
     * 删除指定用户的所有分组访问权限
     *
     * @param userId 用户ID
     */
    void deleteByUserId(Long userId);

    /**
     * 删除指定分组的所有用户访问权限
     *
     * @param groupId 分组ID
     */
    void deleteByGroupId(Long groupId);
}
