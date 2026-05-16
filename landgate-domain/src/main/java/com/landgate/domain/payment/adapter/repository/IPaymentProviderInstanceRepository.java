package com.landgate.domain.payment.adapter.repository;

import com.landgate.domain.payment.model.entity.PaymentProviderInstanceEntity;

import java.util.List;
import java.util.Optional;

/**
 * 支付渠道实例（PaymentProviderInstance）仓储接口 —— 定义领域层所需的支付渠道实例持久化操作契约。
 * <p>
 * 由基础设施层的 RepositoryImpl 实现，通过依赖反转实现领域层不依赖基础设施。
 * 管理各支付渠道（如支付宝、微信支付）的实例配置，支持按渠道标识和启用状态查询。
 */
public interface IPaymentProviderInstanceRepository {

    /**
     * 根据ID查询支付渠道实例
     *
     * @param id 主键ID
     * @return 查询到的实例，不存在返回 Optional.empty()
     */
    Optional<PaymentProviderInstanceEntity> findById(Long id);

    /**
     * 查询指定渠道下所有已启用的实例
     *
     * @param providerKey 支付渠道标识（如 alipay、wechatpay）
     * @return 该渠道下已启用的实例列表
     */
    List<PaymentProviderInstanceEntity> findByProviderKeyAndEnabledTrue(String providerKey);

    /**
     * 查询所有已启用的支付渠道实例
     *
     * @return 所有已启用实例列表
     */
    List<PaymentProviderInstanceEntity> findByEnabledTrue();

    /**
     * 查询指定渠道下的所有实例（含禁用）
     *
     * @param providerKey 支付渠道标识
     * @return 该渠道下的全部实例列表
     */
    List<PaymentProviderInstanceEntity> findByProviderKey(String providerKey);

    /**
     * 保存支付渠道实例（新增或更新）
     *
     * @param entity 实例实体
     * @return 保存后的实例实体
     */
    PaymentProviderInstanceEntity save(PaymentProviderInstanceEntity entity);
}
