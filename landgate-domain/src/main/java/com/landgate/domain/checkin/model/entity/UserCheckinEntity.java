package com.landgate.domain.checkin.model.entity;

import com.landgate.types.enums.CheckinStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 用户签到实体 —— 记录用户每天签到、连续天数和奖励发放状态。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class UserCheckinEntity {

    private Long id;
    private Long userId;
    /** 签到日期，按北京时间计算 */
    private LocalDate signDate;
    /** 连续签到天数 */
    private Integer streakDays;
    /** 本次签到奖励 */
    private BigDecimal rewardAmount;
    /** 关联余额流水 ID */
    private Long balanceTransactionId;
    /** 签到奖励发放状态 */
    @Builder.Default private CheckinStatus status = CheckinStatus.PENDING;
    /** 失败原因 */
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;
}
