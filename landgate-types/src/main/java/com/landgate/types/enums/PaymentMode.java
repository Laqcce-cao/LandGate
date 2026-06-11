package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 支付模式枚举 —— 二维码 / 跳转 / 弹窗。
 * <p>
 * 定义支付页面在前端的展示方式。
 */
@Getter
@AllArgsConstructor
public enum PaymentMode {

    /** 二维码支付 —— 展示支付二维码供用户扫码 */
    QRCODE("qrcode", 1, "二维码支付"),
    /** 跳转支付 —— 跳转到第三方支付页面 */
    REDIRECT("redirect", 2, "跳转支付"),
    /** 弹窗支付 —— 在当前页面弹出支付窗口 */
    POPUP("popup", 3, "弹窗支付");

    /** 代码标识 */
    private final String key;
    /** 数值编码 */
    private final Integer value;
    /** 中文描述 */
    private final String desc;
}
