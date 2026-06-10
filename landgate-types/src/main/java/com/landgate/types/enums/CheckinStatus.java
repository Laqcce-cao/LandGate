package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 签到状态 —— 标识每日签到奖励发放状态。
 */
@Getter
@AllArgsConstructor
public enum CheckinStatus {

    /** 签到记录已创建，等待奖励发放 */
    PENDING("待发放"),
    /** 奖励已发放，签到完成 */
    COMPLETED("已完成"),
    /** 奖励发放失败，可重试 */
    FAILED("失败");

    private final String desc;
}
