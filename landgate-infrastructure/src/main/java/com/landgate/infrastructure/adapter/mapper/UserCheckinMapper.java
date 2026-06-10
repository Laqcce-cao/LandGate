package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.checkin.model.entity.UserCheckinEntity;
import com.landgate.infrastructure.dao.po.UserCheckinPO;
import org.mapstruct.Mapper;

/**
 * 用户签到 MapStruct 映射器 —— UserCheckinPO ↔ UserCheckinEntity 双向转换。
 */
@Mapper(componentModel = "spring")
public interface UserCheckinMapper {
    UserCheckinEntity toEntity(UserCheckinPO po);
    UserCheckinPO toPO(UserCheckinEntity entity);
}
