package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.account.model.entity.ProxyEntity;
import com.landgate.infrastructure.dao.po.ProxyPO;
import org.mapstruct.Mapper;

/**
 * 代理 MapStruct 映射器 —— ProxyPO ↔ ProxyEntity 双向转换。
 * <p>
 * 自动生成实现类（componentModel = "spring"），用于适配器层的数据转换。
 */
@Mapper(componentModel = "spring")
public interface ProxyMapper {
    ProxyEntity toEntity(ProxyPO po);
    ProxyPO toPO(ProxyEntity entity);
}
