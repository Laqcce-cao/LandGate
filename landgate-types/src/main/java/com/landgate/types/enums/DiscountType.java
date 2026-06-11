package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 优惠码折扣类型枚举。
 * <p>
 * 定义优惠码的折扣计算方式。
 */
@Getter
@AllArgsConstructor
public enum DiscountType {

    /** 百分比折扣 —— 按比例减免金额，如 9 折 */
    PERCENTAGE("percentage", 1, "百分比折扣"),
    /** 固定金额减免 —— 直接减去固定金额 */
    FIXED("fixed", 2, "固定金额减免");

    /** 代码标识 */
    private final String key;
    /** 数值编码 */
    private final Integer value;
    /** 中文描述 */
    private final String desc;
}
