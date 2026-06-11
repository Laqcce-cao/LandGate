package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.billing.model.entity.UsageLogEntity;
import com.landgate.infrastructure.dao.po.UsageLogPO;
import org.mapstruct.Mapper;

/**
 * 用量日志 MapStruct 映射器 —— UsageLogPO ↔ UsageLogEntity 双向转换。
 * <p>
 * 自动生成实现类（componentModel = "spring"），用于适配器层的数据转换。
 */
@Mapper(componentModel = "spring")
public interface UsageLogMapper {
    UsageLogEntity toEntity(UsageLogPO po);
    UsageLogPO toPO(UsageLogEntity entity);
}
