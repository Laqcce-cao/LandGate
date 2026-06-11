package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.payment.model.entity.PaymentProviderInstanceEntity;
import com.landgate.infrastructure.dao.po.PaymentProviderInstancePO;
import org.mapstruct.Mapper;

/**
 * 支付服务商实例 MapStruct 映射器 —— PaymentProviderInstancePO ↔ PaymentProviderInstanceEntity 双向转换。
 * <p>
 * 自动生成实现类（componentModel = "spring"），用于适配器层的数据转换。
 */
@Mapper(componentModel = "spring")
public interface PaymentProviderInstanceMapper {
    PaymentProviderInstanceEntity toEntity(PaymentProviderInstancePO po);
    PaymentProviderInstancePO toPO(PaymentProviderInstanceEntity entity);
}
