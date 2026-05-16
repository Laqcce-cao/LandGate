package com.landgate.domain.marketing.adapter.repository;

import com.landgate.domain.marketing.model.entity.PromoCodeEntity;

import java.util.List;
import java.util.Optional;

/**
 * 优惠码（PromoCode）仓储接口 —— 定义领域层所需的促销优惠码持久化操作契约。
 * <p>
 * 由基础设施层的 RepositoryImpl 实现，通过依赖反转实现领域层不依赖基础设施。
 * 管理促销优惠码的增删改查，支持按码值查询和查询所有启用的优惠码。
 */
public interface IPromoCodeRepository {

    /**
     * 根据ID查询优惠码
     *
     * @param id 主键ID
     * @return 查询到的优惠码，不存在返回 Optional.empty()
     */
    Optional<PromoCodeEntity> findById(Long id);

    /**
     * 根据码值查询优惠码
     *
     * @param code 优惠码字符串
     * @return 查询到的优惠码，不存在返回 Optional.empty()
     */
    Optional<PromoCodeEntity> findByCode(String code);

    /**
     * 查询所有优惠码
     *
     * @return 全部优惠码列表
     */
    List<PromoCodeEntity> findAll();

    /**
     * 查询所有已启用的优惠码
     *
     * @return 已启用优惠码列表
     */
    List<PromoCodeEntity> findByEnabledTrue();

    /**
     * 保存优惠码（新增或更新）
     *
     * @param entity 优惠码实体
     * @return 保存后的优惠码实体
     */
    PromoCodeEntity save(PromoCodeEntity entity);

    /**
     * 删除优惠码
     *
     * @param entity 待删除的优惠码实体
     */
    void delete(PromoCodeEntity entity);
}
