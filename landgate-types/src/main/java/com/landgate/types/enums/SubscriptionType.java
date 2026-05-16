package com.landgate.types.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订阅类型枚举。
 * <p>
 * 区分分组的服务等级：标准版按量计费，订阅版按月/年付费。
 */
@Getter
@AllArgsConstructor
public enum SubscriptionType {

    /** 标准版 —— 按实际使用量计费，无月费 */
    STANDARD("standard", 1, "标准版"),
    /** 订阅版 —— 按月或按年订阅，享受更低单价 */
    SUBSCRIPTION("subscription", 2, "订阅版");

    /** 代码标识 */
    @JsonValue
    private final String key;
    /** 数值编码 */
    private final Integer value;
    /** 中文描述 */
    private final String desc;

    @JsonCreator
    public static SubscriptionType from(String value) {
        if (value == null) return null;
        for (SubscriptionType t : values()) {
            if (t.key.equalsIgnoreCase(value) || t.name().equalsIgnoreCase(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown subscription type: " + value);
    }
}
