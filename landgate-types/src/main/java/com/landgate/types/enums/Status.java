package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通用状态枚举 —— 对应 Go 版本 status 常量。
 * <p>
 * 用于 User、Account、Group、ApiKey、Proxy、PaymentProviderInstance 等实体的状态标识。
 */
@Getter
@AllArgsConstructor
public enum Status {

    /** 激活 —— 对象处于正常可用状态 */
    ACTIVE("active", 1, "激活"),
    /** 禁用 —— 对象已被管理员禁用，不可使用 */
    DISABLED("disabled", 0, "禁用"),
    /** 异常 —— 对象因错误进入异常状态，需排查 */
    ERROR("error", -1, "异常"),
    /** 未使用 —— 资源尚未被使用（如兑换码） */
    UNUSED("unused", 10, "未使用"),
    /** 已使用 —— 资源已被消费 */
    USED("used", 20, "已使用"),
    /** 已过期 —— 资源已超过有效期限 */
    EXPIRED("expired", 30, "已过期");

    /** 代码标识（小写英文，对应数据库存储语义） */
    private final String key;
    /** 数值编码（正数为正常状态，负数为异常/废弃状态） */
    private final Integer value;
    /** 中文描述 */
    private final String desc;
}
