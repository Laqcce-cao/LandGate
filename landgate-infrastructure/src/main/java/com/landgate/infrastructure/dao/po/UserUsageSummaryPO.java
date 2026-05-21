package com.landgate.infrastructure.dao.po;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 用户用量聚合结果的持久化对象 —— 对应 aggregateByUser 查询结果。
 */
@Getter
@Setter
public class UserUsageSummaryPO {

    private Long userId;
    private String username;
    private String email;
    private BigDecimal totalCost;
    private Long totalTokens;
    private Long callCount;
}
