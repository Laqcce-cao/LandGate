package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.marketing.model.entity.PromoCodeEntity;
import com.landgate.infrastructure.dao.po.PromoCodePO;
import org.mapstruct.Mapper;

/**
 * 优惠码 MapStruct 映射器 —— PromoCodePO ↔ PromoCodeEntity 双向转换。
 * <p>
 * 自动生成实现类（componentModel = "spring"），用于适配器层的数据转换。
 */
@Mapper(componentModel = "spring")
public interface PromoCodeMapper {
    PromoCodeEntity toEntity(PromoCodePO po);
    PromoCodePO toPO(PromoCodeEntity entity);
}
