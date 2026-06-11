package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 兑换码类型枚举 —— 余额 / 订阅。
 * <p>
 * 定义兑换码兑换后用户获得的是账户余额还是订阅时长。
 */
@Getter
@AllArgsConstructor
public enum RedeemCodeType {

    /** 余额兑换 —— 兑换后增加账户余额 */
    BALANCE("balance", 1, "余额兑换"),
    /** 订阅兑换 —— 兑换后获得订阅天数 */
    SUBSCRIPTION("subscription", 2, "订阅兑换");

    /** 代码标识 */
    private final String key;
    /** 数值编码 */
    private final Integer value;
    /** 中文描述 */
    private final String desc;
}
