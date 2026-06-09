package com.landgate.domain.payment.adapter.repository;

import com.landgate.domain.payment.model.entity.PaymentOrderEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 支付订单（PaymentOrder）仓储接口 —— 定义领域层所需的支付订单持久化操作契约。
 * <p>
 * 由基础设施层的 RepositoryImpl 实现，通过依赖反转实现领域层不依赖基础设施。
 * 管理支付订单的完整生命周期，支持按商户订单号、支付流水号、充值码、
 * 用户ID、状态及过期时间等多维度查询，以及分页和统计操作。
 */
public interface IPaymentOrderRepository {

    /**
     * 根据ID查询支付订单
     *
     * @param id 主键ID
     * @return 查询到的订单，不存在返回 Optional.empty()
     */
    Optional<PaymentOrderEntity> findById(Long id);

    /**
     * 根据商户订单号查询支付订单
     *
     * @param outTradeNo 商户侧生成的唯一订单号
     * @return 查询到的订单，不存在返回 Optional.empty()
     */
    Optional<PaymentOrderEntity> findByOutTradeNo(String outTradeNo);

    /**
     * 根据支付平台流水号查询支付订单
     *
     * @param paymentTradeNo 支付渠道返回的交易流水号
     * @return 查询到的订单，不存在返回 Optional.empty()
     */
    Optional<PaymentOrderEntity> findByPaymentTradeNo(String paymentTradeNo);

    /**
     * 根据充值码查询关联的支付订单
     *
     * @param rechargeCode 充值兑换码
     * @return 查询到的订单，不存在返回 Optional.empty()
     */
    Optional<PaymentOrderEntity> findByRechargeCode(String rechargeCode);

    /**
     * 查询指定用户的所有支付订单
     *
     * @param userId 用户ID
     * @return 该用户的订单列表
     */
    List<PaymentOrderEntity> findByUserId(Long userId);

    /**
     * 根据状态查询支付订单
     *
     * @param status 订单状态
     * @return 该状态下的订单列表
     */
    List<PaymentOrderEntity> findByStatus(String status);

    /**
     * 分页查询指定用户的余额充值订单。
     *
     * @param userId 用户 ID
     * @param offset 起始偏移量
     * @param size 每页数量
     * @return 充值订单列表
     */
    List<PaymentOrderEntity> findRechargeRecordsByUserId(Long userId, int offset, int size);

    /**
     * 统计指定用户的余额充值订单数量。
     *
     * @param userId 用户 ID
     * @return 充值订单总数
     */
    long countRechargeRecordsByUserId(Long userId);

    /**
     * 根据用户ID和状态联合查询支付订单
     *
     * @param userId 用户ID
     * @param status 订单状态
     * @return 符合条件的订单列表
     */
    List<PaymentOrderEntity> findByUserIdAndStatus(Long userId, String status);

    /**
     * 查询指定状态且已过期的支付订单（用于定时任务清理）
     *
     * @param status    订单状态
     * @param expiresAt 过期截止时间，查询过期时间早于此值的订单
     * @return 过期的订单列表
     */
    List<PaymentOrderEntity> findByStatusAndExpiresAtBefore(String status, Instant expiresAt);

    /**
     * 保存支付订单（新增或更新）
     *
     * @param entity 订单实体
     * @return 保存后的订单实体
     */
    PaymentOrderEntity save(PaymentOrderEntity entity);

    /**
     * 查询所有支付订单
     *
     * @return 全部订单列表
     */
    List<PaymentOrderEntity> findAll();

    /**
     * 统计支付订单总数
     *
     * @return 订单总数
     */
    long count();
}
