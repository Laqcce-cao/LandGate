package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.marketing.model.entity.AnnouncementEntity;
import com.landgate.infrastructure.dao.po.AnnouncementPO;
import org.mapstruct.Mapper;

/**
 * 公告 MapStruct 映射器 —— AnnouncementPO ↔ AnnouncementEntity 双向转换。
 * <p>
 * 自动生成实现类（componentModel = "spring"），用于适配器层的数据转换。
 */
@Mapper(componentModel = "spring")
public interface AnnouncementMapper {
    AnnouncementEntity toEntity(AnnouncementPO po);
    AnnouncementPO toPO(AnnouncementEntity entity);
}
