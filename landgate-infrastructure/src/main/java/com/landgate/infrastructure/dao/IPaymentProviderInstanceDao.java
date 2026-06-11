package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.PaymentProviderInstancePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 支付提供商实例 DAO —— MyBatis Mapper 接口。
 * <p>
 * XML 映射文件：mapper/PaymentProviderInstanceMapper.xml
 * 对应表：payment_provider_instances（无软删除）
 */
@Mapper
public interface IPaymentProviderInstanceDao {

    /** 根据 ID 查询实例 */
    PaymentProviderInstancePO selectById(@Param("id") Long id);

    /** 插入实例，useGeneratedKeys 回填 ID */
    int insert(PaymentProviderInstancePO instance);

    /** 更新实例所有字段 */
    int update(PaymentProviderInstancePO instance);

    /** 查询所有实例 */
    List<PaymentProviderInstancePO> selectAll();

    /** 统计实例总数 */
    long countAll();

    /** 查询指定 provider 且已启用的实例 */
    List<PaymentProviderInstancePO> selectByProviderKeyAndEnabled(@Param("providerKey") String providerKey);

    /** 查询所有已启用的实例 */
    List<PaymentProviderInstancePO> selectEnabled();

    /** 根据提供商标识查询实例 */
    List<PaymentProviderInstancePO> selectByProviderKey(@Param("providerKey") String providerKey);
}
