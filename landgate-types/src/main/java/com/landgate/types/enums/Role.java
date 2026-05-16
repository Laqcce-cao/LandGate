package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户角色枚举。
 * <p>
 * 控制用户在后端的权限级别：管理员可访问管理后台，普通用户仅能使用 API。
 */
@Getter
@AllArgsConstructor
public enum Role {

    /** 管理员 */
    ADMIN("admin", 1, "管理员"),
    /** 普通用户 */
    USER("user", 2, "普通用户");

    /** 代码标识 */
    private final String key;
    /** 数值编码 */
    private final Integer value;
    /** 中文描述 */
    private final String desc;
}
