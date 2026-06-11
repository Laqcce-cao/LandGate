package com.landgate.domain.marketing.adapter.repository;

import com.landgate.domain.marketing.model.entity.RedeemCodeEntity;

import java.util.List;
import java.util.Optional;

/**
 * 兑换码（RedeemCode）仓储接口 —— 定义领域层所需的兑换码持久化操作契约。
 * <p>
 * 由基础设施层的 RepositoryImpl 实现，通过依赖反转实现领域层不依赖基础设施。
 * 管理兑换码的增删改查，支持按码值、创建者查询及查询所有启用的兑换码。
 */
public interface IRedeemCodeRepository {

    /**
     * 根据ID查询兑换码
     *
     * @param id 主键ID
     * @return 查询到的兑换码，不存在返回 Optional.empty()
     */
    Optional<RedeemCodeEntity> findById(Long id);

    /**
     * 根据码值查询兑换码
     *
     * @param code 兑换码字符串
     * @return 查询到的兑换码，不存在返回 Optional.empty()
     */
    Optional<RedeemCodeEntity> findByCode(String code);

    /**
     * 查询所有兑换码
     *
     * @return 全部兑换码列表
     */
    List<RedeemCodeEntity> findAll();

    /**
     * 查询所有已启用的兑换码
     *
     * @return 已启用兑换码列表
     */
    List<RedeemCodeEntity> findByEnabledTrue();

    /**
     * 根据创建者查询其创建的所有兑换码
     *
     * @param createdBy 创建者用户ID
     * @return 该创建者生成的兑换码列表
     */
    List<RedeemCodeEntity> findByCreatedBy(Long createdBy);

    /**
     * 保存兑换码（新增或更新）
     *
     * @param entity 兑换码实体
     * @return 保存后的兑换码实体
     */
    RedeemCodeEntity save(RedeemCodeEntity entity);

    /**
     * 删除兑换码
     *
     * @param entity 待删除的兑换码实体
     */
    void delete(RedeemCodeEntity entity);
}
