package com.landgate.api.checkin.dto;

import java.math.BigDecimal;

/**
 * 签到奖励规则 DTO —— 描述连续签到第 N 天对应奖励。
 */
public record RewardRuleDTO(
        Integer day,
        BigDecimal reward
) {}
