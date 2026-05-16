package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 公告类型枚举。
 * <p>
 * 定义系统公告的展示样式和优先级。
 */
@Getter
@AllArgsConstructor
public enum AnnouncementType {

    /** 信息通知 —— 普通信息公告，蓝色样式 */
    INFO("info", 1, "信息通知"),
    /** 警告通知 —— 需要用户注意的警告，橙色样式 */
    WARNING("warning", 2, "警告通知"),
    /** 成功通知 —— 正面消息公告，绿色样式 */
    SUCCESS("success", 3, "成功通知"),
    /** 错误通知 —— 严重问题公告，红色样式 */
    ERROR("error", -1, "错误通知");

    /** 代码标识 */
    private final String key;
    /** 数值编码 */
    private final Integer value;
    /** 中文描述 */
    private final String desc;
}
