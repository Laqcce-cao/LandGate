package com.landgate.infrastructure.dao.po;

import com.landgate.types.enums.CheckinStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 用户签到持久化对象 —— 对应 user_checkins 表。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class UserCheckinPO extends BasePO {

    private Long id;
    private Long userId;
    private LocalDate signDate;
    private Integer streakDays;
    private BigDecimal rewardAmount;
    private Long balanceTransactionId;
    private CheckinStatus status;
    private String failureReason;
}
