package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.group.model.entity.UserAllowedGroupEntity;
import com.landgate.infrastructure.dao.po.UserAllowedGroupPO;
import org.mapstruct.Mapper;

/**
 * 用户-分组授权 MapStruct 映射器 —— UserAllowedGroupPO ↔ UserAllowedGroupEntity 双向转换。
 * <p>
 * 自动生成实现类（componentModel = "spring"），用于适配器层的数据转换。
 */
@Mapper(componentModel = "spring")
public interface UserAllowedGroupMapper {
    UserAllowedGroupEntity toEntity(UserAllowedGroupPO po);
    UserAllowedGroupPO toPO(UserAllowedGroupEntity entity);
}
