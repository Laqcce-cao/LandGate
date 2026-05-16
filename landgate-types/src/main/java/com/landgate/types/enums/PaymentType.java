package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 支付渠道枚举。
 * <p>
 * 标识用户支付时使用的支付服务提供商。
 */
@Getter
@AllArgsConstructor
public enum PaymentType {

    /** 支付宝 —— 支持扫码、H5、App 支付 */
    ALIPAY("alipay", 1, "支付宝"),
    /** 微信支付 —— 支持扫码、H5、JSAPI 支付 */
    WXPAY("wxpay", 2, "微信支付"),
    /** Stripe —— 国际信用卡支付 */
    STRIPE("stripe", 3, "Stripe"),
    /** EasyPay —— 易支付聚合支付 */
    EASYPAY("easypay", 4, "EasyPay"),
    /** Airwallex —— 空中云汇跨境支付 */
    AIRWALLEX("airwallex", 5, "Airwallex");

    /** 代码标识 */
    private final String key;
    /** 数值编码 */
    private final Integer value;
    /** 中文描述 */
    private final String desc;
}
