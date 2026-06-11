package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.infrastructure.dao.po.AccountPO;
import org.mapstruct.Mapper;

/**
 * 账号 MapStruct 映射器 —— AccountPO ↔ AccountEntity 双向转换。
 * <p>
 * 自动生成实现类（componentModel = "spring"），用于适配器层的数据转换。
 */
@Mapper(componentModel = "spring")
public interface AccountMapper {
    AccountEntity toEntity(AccountPO po);
    AccountPO toPO(AccountEntity entity);
}
