package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.marketing.model.entity.RedeemCodeEntity;
import com.landgate.infrastructure.dao.po.RedeemCodePO;
import org.mapstruct.Mapper;

/**
 * 兑换码 MapStruct 映射器 —— RedeemCodePO ↔ RedeemCodeEntity 双向转换。
 * <p>
 * 自动生成实现类（componentModel = "spring"），用于适配器层的数据转换。
 */
@Mapper(componentModel = "spring")
public interface RedeemCodeMapper {
    RedeemCodeEntity toEntity(RedeemCodePO po);
    RedeemCodePO toPO(RedeemCodeEntity entity);
}
