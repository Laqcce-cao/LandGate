package com.landgate.domain.checkin.model.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 签到状态实体 —— 领域层返回今日签到状态、连续天数和奖励规则。
 */
public record CheckinStatusEntity(
        /** 今日日期，按 Asia/Shanghai 计算 */
        LocalDate today,
        /** 今日是否已有签到记录 */
        Boolean signedToday,
        /** 当前是否允许点击签到按钮 */
        Boolean canCheckin,
        /** 今日签到状态：NONE、PENDING、COMPLETED、FAILED */
        String todayStatus,
        /** 当前展示的连续签到天数 */
        Integer streakDays,
        /** 今日可获得或已获得的签到奖励 */
        BigDecimal todayReward,
        /** 明日继续签到可获得的奖励 */
        BigDecimal nextReward,
        /** 7 天封顶奖励规则 */
        List<CheckinRewardRuleEntity> rewardRules,
        /** 今日签到记录；今日未签到时为空 */
        UserCheckinEntity todayRecord
) {}
