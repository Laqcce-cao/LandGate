package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.PaymentOrderPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 支付订单 DAO —— MyBatis Mapper 接口。
 * <p>
 * XML 映射文件：mapper/PaymentOrderMapper.xml
 * 对应表：payment_orders（无软删除）
 */
@Mapper
public interface IPaymentOrderDao {

    /** 根据 ID 查询订单 */
    PaymentOrderPO selectById(@Param("id") Long id);

    /** 插入订单，useGeneratedKeys 回填 ID */
    int insert(PaymentOrderPO order);

    /** 更新订单所有字段 */
    int update(PaymentOrderPO order);

    /** 查询所有订单 */
    List<PaymentOrderPO> selectAll();

    /** 统计订单总数 */
    long countAll();

    /** 根据商户订单号查询 */
    PaymentOrderPO selectByOutTradeNo(@Param("outTradeNo") String outTradeNo);

    /** 根据支付平台交易号查询 */
    PaymentOrderPO selectByPaymentTradeNo(@Param("paymentTradeNo") String paymentTradeNo);

    /** 根据充值码查询 */
    PaymentOrderPO selectByRechargeCode(@Param("rechargeCode") String rechargeCode);

    /** 根据用户 ID 查询订单列表 */
    List<PaymentOrderPO> selectByUserId(@Param("userId") Long userId);

    /** 分页查询指定用户的余额充值订单 */
    List<PaymentOrderPO> selectRechargeRecordsByUserId(@Param("userId") Long userId,
                                                       @Param("offset") int offset,
                                                       @Param("size") int size);

    /** 统计指定用户的余额充值订单数量 */
    long countRechargeRecordsByUserId(@Param("userId") Long userId);

    /** 根据状态查询订单列表 */
    List<PaymentOrderPO> selectByStatus(@Param("status") String status);

    /** 根据用户 ID 和状态查询订单列表 */
    List<PaymentOrderPO> selectByUserIdAndStatus(@Param("userId") Long userId, @Param("status") String status);

    /** 查询指定状态且已过期的订单 */
    List<PaymentOrderPO> selectByStatusAndExpiresAtBefore(@Param("status") String status, @Param("expiresAt") Instant expiresAt);
}
