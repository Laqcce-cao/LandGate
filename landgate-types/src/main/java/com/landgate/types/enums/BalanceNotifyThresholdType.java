package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 余额通知阈值类型枚举。
 * <p>
 * 定义余额告警的触发方式：固定金额阈值或百分比阈值。
 */
@Getter
@AllArgsConstructor
public enum BalanceNotifyThresholdType {

    /** 固定金额 —— 余额低于指定金额时触发通知 */
    FIXED("fixed", 1, "固定金额"),
    /** 百分比 —— 余额低于初始值的一定百分比时触发通知 */
    PERCENTAGE("percentage", 2, "百分比");

    /** 代码标识 */
    private final String key;
    /** 数值编码 */
    private final Integer value;
    /** 中文描述 */
    private final String desc;
}
