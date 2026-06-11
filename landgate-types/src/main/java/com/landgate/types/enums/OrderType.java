package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单类型枚举 —— 余额充值 / 订阅购买。
 * <p>
 * 区分用户支付订单的业务类型。
 */
@Getter
@AllArgsConstructor
public enum OrderType {

    /** 余额充值 —— 用户充值账户余额 */
    BALANCE("balance", 1, "余额充值"),
    /** 订阅购买 —— 用户购买或续费订阅套餐 */
    SUBSCRIPTION("subscription", 2, "订阅购买");

    /** 代码标识 */
    private final String key;
    /** 数值编码 */
    private final Integer value;
    /** 中文描述 */
    private final String desc;
}
