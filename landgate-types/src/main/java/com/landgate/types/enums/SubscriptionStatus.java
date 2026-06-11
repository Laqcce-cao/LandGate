package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订阅状态枚举。
 * <p>
 * 标识用户订阅的当前生命周期状态。
 */
@Getter
@AllArgsConstructor
public enum SubscriptionStatus {

    /** 订阅生效中 —— 订阅在有效期内，功能正常 */
    ACTIVE("active", 1, "订阅生效中"),
    /** 订阅已过期 —— 订阅到期未续费，降级为标准版 */
    EXPIRED("expired", 0, "订阅已过期"),
    /** 订阅已暂停 —— 因欠费或违规被暂停使用 */
    SUSPENDED("suspended", -1, "订阅已暂停");

    /** 代码标识 */
    private final String key;
    /** 数值编码 */
    private final Integer value;
    /** 中文描述 */
    private final String desc;
}
