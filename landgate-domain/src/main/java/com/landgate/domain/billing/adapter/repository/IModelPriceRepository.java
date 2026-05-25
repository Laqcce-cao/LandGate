package com.landgate.domain.billing.adapter.repository;

import com.landgate.domain.billing.model.entity.ModelPriceEntity;

import java.util.List;
import java.util.Optional;

/**
 * 模型定价（ModelPrice）仓储接口 —— 定义领域层所需的模型定价持久化操作契约。
 * <p>
 * 由基础设施层的 RepositoryImpl 实现，通过依赖反转实现领域层不依赖基础设施。
 * 管理各AI模型的定价信息，支持按模型名、分组、平台等维度查询，以及分页和增删操作。
 */
public interface IModelPriceRepository {

    /**
     * 根据ID查询定价记录
     *
     * @param id 主键ID
     * @return 查询到的定价实体，不存在返回 Optional.empty()
     */
    Optional<ModelPriceEntity> findById(Long id);

    /**
     * 根据模型名查询定价
     *
     * @param model   模型名称（如 gpt-4、claude-3）
     * @return 匹配的定价实体，不存在返回 Optional.empty()
     */
    Optional<ModelPriceEntity> findByModel(String model);

    /**
     * 根据平台查询该平台下所有模型的定价
     *
     * @param platform 平台标识
     * @return 该平台下的所有定价记录
     */
    List<ModelPriceEntity> findByPlatform(String platform);

    /**
     * 分页查询所有定价记录
     *
     * @param page 页码（从0开始）
     * @param size 每页数量
     * @return 当前页的定价记录列表
     */
    List<ModelPriceEntity> findAll(int page, int size);

    /**
     * 统计定价记录总数
     *
     * @return 定价记录总数
     */
    long count();

    /**
     * 保存定价记录（新增或更新）
     *
     * @param entity 定价实体
     * @return 保存后的定价实体
     */
    ModelPriceEntity save(ModelPriceEntity entity);

    /**
     * 根据ID删除定价记录
     *
     * @param id 主键ID
     */
    void deleteById(Long id);
}
