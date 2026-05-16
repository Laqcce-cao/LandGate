package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.infrastructure.dao.po.UserPO;
import org.mapstruct.Mapper;

/**
 * 用户 MapStruct 映射器 —— UserPO ↔ UserEntity 双向转换。
 * <p>
 * 自动生成实现类（componentModel = "spring"），用于适配器层的数据转换。
 */
@Mapper(componentModel = "spring")
public interface UserMapper {
    UserEntity toEntity(UserPO po);
    UserPO toPO(UserEntity entity);
}
