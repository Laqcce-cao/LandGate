package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.group.model.entity.AccountGroupEntity;
import com.landgate.infrastructure.dao.po.AccountGroupPO;
import org.mapstruct.Mapper;

/**
 * 账号-分组关联 MapStruct 映射器 —— AccountGroupPO ↔ AccountGroupEntity 双向转换。
 * <p>
 * 自动生成实现类（componentModel = "spring"），用于适配器层的数据转换。
 */
@Mapper(componentModel = "spring")
public interface AccountGroupMapper {
    AccountGroupEntity toEntity(AccountGroupPO po);
    AccountGroupPO toPO(AccountGroupEntity entity);
}
