package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.billing.model.entity.ModelPriceEntity;
import com.landgate.infrastructure.dao.po.ModelPricePO;
import com.landgate.types.enums.Platform;
import org.mapstruct.Mapper;

/**
 * 模型价格 MapStruct 映射器 —— ModelPricePO ↔ ModelPriceEntity 双向转换。
 * <p>
 * 包含 String ↔ Platform 枚举的自定义转换方法。
 * 自动生成实现类（componentModel = "spring"），用于适配器层的数据转换。
 */
@Mapper(componentModel = "spring")
public interface ModelPriceMapper {

    ModelPriceEntity toEntity(ModelPricePO po);

    ModelPricePO toPO(ModelPriceEntity entity);

    /** String → Platform 枚举转换（供 MapStruct 生成 toEntity 时使用） */
    default Platform mapPlatform(String value) {
        return value == null ? null : Platform.valueOf(value);
    }

    /** Platform 枚举 → String 转换（供 MapStruct 生成 toPO 时使用） */
    default String mapPlatform(Platform value) {
        return value == null ? null : value.name();
    }
}
