package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.auth.model.entity.ApiKeyEntity;
import com.landgate.infrastructure.dao.po.ApiKeyPO;
import org.mapstruct.Mapper;

/**
 * API Key MapStruct 映射器 —— ApiKeyPO ↔ ApiKeyEntity 双向转换。
 * <p>
 * 自动生成实现类（componentModel = "spring"），用于适配器层的数据转换。
 */
@Mapper(componentModel = "spring")
public interface ApiKeyMapper {
    ApiKeyEntity toEntity(ApiKeyPO po);
    ApiKeyPO toPO(ApiKeyEntity entity);
}
