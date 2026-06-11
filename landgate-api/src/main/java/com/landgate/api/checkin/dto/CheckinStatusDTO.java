package com.landgate.api.checkin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 签到状态 DTO —— 页面初始化时返回今日状态、连续天数和奖励规则。
 */
public record CheckinStatusDTO(
        LocalDate today,
        Boolean signedToday,
        Boolean canCheckin,
        String todayStatus,
        Integer streakDays,
        BigDecimal todayReward,
        BigDecimal nextReward,
        List<RewardRuleDTO> rewardRules,
        CheckinRecordDTO todayRecord
) {}
