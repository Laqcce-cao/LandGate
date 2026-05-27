package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.billing.model.entity.ModelPriceEntity;
import com.landgate.infrastructure.dao.po.ModelPricePO;
import org.mapstruct.Mapper;

/**
 * 模型价格 MapStruct 映射器 —— ModelPricePO ↔ ModelPriceEntity 双向转换。
 * <p>
 * 自动生成实现类（componentModel = "spring"），用于适配器层的数据转换。
 */
@Mapper(componentModel = "spring")
public interface ModelPriceMapper {

    ModelPriceEntity toEntity(ModelPricePO po);

    ModelPricePO toPO(ModelPriceEntity entity);
}
