package com.landgate.api.checkin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 签到记录 DTO —— 用户侧签到记录列表和今日签到记录展示对象。
 */
public record CheckinRecordDTO(
        Long id,
        LocalDate signDate,
        Integer streakDays,
        BigDecimal rewardAmount,
        String status,
        Long balanceTransactionId,
        Instant createdAt
) {}
