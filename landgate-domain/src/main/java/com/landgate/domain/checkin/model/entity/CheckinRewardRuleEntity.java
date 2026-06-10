package com.landgate.domain.checkin.model.entity;

import java.math.BigDecimal;

/**
 * 签到奖励规则实体 —— 描述连续签到第 N 天对应的奖励金额。
 */
public record CheckinRewardRuleEntity(
        /** 连续签到第几天，超过 7 天按第 7 天奖励封顶 */
        Integer day,
        /** 对应奖励金额 */
        BigDecimal reward
) {}
