package com.landgate.infrastructure.adapter.mapper;

import com.landgate.domain.balance.model.entity.BalanceTransactionEntity;
import com.landgate.infrastructure.dao.po.BalanceTransactionPO;
import org.mapstruct.Mapper;

/**
 * 余额流水 MapStruct 映射器 —— BalanceTransactionPO ↔ BalanceTransactionEntity 双向转换。
 */
@Mapper(componentModel = "spring")
public interface BalanceTransactionMapper {
    BalanceTransactionEntity toEntity(BalanceTransactionPO po);
    BalanceTransactionPO toPO(BalanceTransactionEntity entity);
}
