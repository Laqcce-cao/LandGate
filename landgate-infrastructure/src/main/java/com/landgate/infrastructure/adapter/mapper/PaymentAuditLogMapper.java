package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.payment.model.entity.PaymentAuditLogEntity;
import com.landgate.infrastructure.dao.po.PaymentAuditLogPO;
import org.mapstruct.Mapper;

/**
 * 支付审计日志 MapStruct 映射器 —— PaymentAuditLogPO ↔ PaymentAuditLogEntity 双向转换。
 * <p>
 * 自动生成实现类（componentModel = "spring"），用于适配器层的数据转换。
 */
@Mapper(componentModel = "spring")
public interface PaymentAuditLogMapper {
    PaymentAuditLogEntity toEntity(PaymentAuditLogPO po);
    PaymentAuditLogPO toPO(PaymentAuditLogEntity entity);
}
