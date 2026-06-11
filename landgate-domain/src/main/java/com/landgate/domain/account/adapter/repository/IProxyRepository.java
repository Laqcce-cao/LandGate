package com.landgate.domain.account.adapter.repository;

import com.landgate.domain.account.model.entity.ProxyEntity;

import java.util.List;
import java.util.Optional;

/**
 * 代理（Proxy）仓储接口 —— 定义领域层所需的代理配置持久化操作契约。
 * <p>
 * 由基础设施层的 RepositoryImpl 实现，通过依赖反转实现领域层不依赖基础设施。
 * 管理代理服务器配置，提供按ID查找、保存及全量查询操作。
 */
public interface IProxyRepository {

    /**
     * 根据ID查询代理配置
     *
     * @param id 主键ID
     * @return 查询到的代理实体，不存在返回 Optional.empty()
     */
    Optional<ProxyEntity> findById(Long id);

    /**
     * 保存代理配置（新增或更新）
     *
     * @param entity 代理实体
     * @return 保存后的代理实体
     */
    ProxyEntity save(ProxyEntity entity);

    /**
     * 查询所有代理配置
     *
     * @return 全部代理列表
     */
    List<ProxyEntity> findAll();
}
