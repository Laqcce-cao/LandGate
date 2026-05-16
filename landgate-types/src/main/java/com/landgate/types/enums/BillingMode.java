package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 计费模式枚举 —— token / 按次 / 图片。
 * <p>
 * 定义上游 API 调用的计量和计费方式。
 */
@Getter
@AllArgsConstructor
public enum BillingMode {

    /** 按 Token 计费 —— 根据 input/output token 消耗量计费 */
    TOKEN("token", 1, "按 Token 计费"),
    /** 按次计费 —— 每次 API 调用固定收费 */
    PER_REQUEST("per_request", 2, "按次计费"),
    /** 按图片计费 —— 根据生成/处理的图片数量计费 */
    IMAGE("image", 3, "按图片计费");

    /** 代码标识 */
    private final String key;
    /** 数值编码 */
    private final Integer value;
    /** 中文描述 */
    private final String desc;
}
