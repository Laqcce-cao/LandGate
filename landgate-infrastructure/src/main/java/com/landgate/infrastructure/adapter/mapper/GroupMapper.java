package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.infrastructure.dao.po.GroupPO;
import org.mapstruct.Mapper;

/**
 * 分组 MapStruct 映射器 —— GroupPO ↔ GroupEntity 双向转换。
 * <p>
 * 自动生成实现类（componentModel = "spring"），用于适配器层的数据转换。
 */
@Mapper(componentModel = "spring")
public interface GroupMapper {
    GroupEntity toEntity(GroupPO po);
    GroupPO toPO(GroupEntity entity);
}
