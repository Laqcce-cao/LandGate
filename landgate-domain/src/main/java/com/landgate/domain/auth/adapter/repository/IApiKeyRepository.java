package com.landgate.domain.auth.adapter.repository;

import com.landgate.domain.auth.model.entity.ApiKeyEntity;

import java.util.List;
import java.util.Optional;

/**
 * API密钥（ApiKey）仓储接口 —— 定义领域层所需的密钥持久化操作契约。
 * <p>
 * 由基础设施层的 RepositoryImpl 实现，通过依赖反转实现领域层不依赖基础设施。
 * 管理API密钥的增删改查，支持按密钥值、用户ID等维度查询。
 */
public interface IApiKeyRepository {

    /**
     * 根据密钥值查询API密钥
     *
     * @param key API密钥字符串
     * @return 查询到的密钥实体，不存在返回 Optional.empty()
     */
    Optional<ApiKeyEntity> findByKey(String key);

    /**
     * 根据用户ID查询其下所有API密钥
     *
     * @param userId 用户ID
     * @return 该用户的所有API密钥列表
     */
    List<ApiKeyEntity> findByUserId(Long userId);

    /**
     * 保存API密钥（新增或更新）
     *
     * @param entity 密钥实体
     * @return 保存后的密钥实体
     */
    ApiKeyEntity save(ApiKeyEntity entity);

    /**
     * 根据ID查询API密钥
     *
     * @param id 主键ID
     * @return 查询到的密钥实体，不存在返回 Optional.empty()
     */
    Optional<ApiKeyEntity> findById(Long id);

    /**
     * 根据ID删除API密钥
     *
     * @param id 主键ID
     */
    void deleteById(Long id);
}
