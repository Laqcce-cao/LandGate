package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 支付订单状态机枚举 —— 对应 PaymentOrder 的状态常量。
 * <p>
 * 状态流转：
 * <pre>
 * PENDING → PAID → RECHARGING → COMPLETED
 * PENDING → EXPIRED
 * PENDING → CANCELLED
 * PAID/COMPLETED → REFUND_REQUESTED → REFUNDING → REFUNDED / PARTIALLY_REFUNDED
 * REFUNDING → REFUND_FAILED
 * </pre>
 */
@Getter
@AllArgsConstructor
public enum OrderStatus {

    /** 待支付 —— 订单已创建，等待用户完成支付 */
    PENDING("pending", 0, "待支付"),
    /** 已支付 —— 用户已完成付款，等待充值到账 */
    PAID("paid", 1, "已支付"),
    /** 充值中 —— 正在执行余额充值或订阅开通 */
    RECHARGING("recharging", 2, "充值中"),
    /** 已完成 —— 订单全部处理完毕 */
    COMPLETED("completed", 3, "已完成"),
    /** 失败 —— 订单处理失败 */
    FAILED("failed", -1, "失败"),
    /** 已过期 —— 订单超过支付有效期自动关闭 */
    EXPIRED("expired", -2, "已过期"),
    /** 已取消 —— 用户或系统主动取消订单 */
    CANCELLED("cancelled", -3, "已取消"),
    /** 退款申请中 —— 用户已提交退款请求，等待审核 */
    REFUND_REQUESTED("refund_requested", 10, "退款申请中"),
    /** 退款处理中 —— 退款申请已通过，正在执行退款 */
    REFUNDING("refunding", 11, "退款处理中"),
    /** 已退款 —— 退款全额到账 */
    REFUNDED("refunded", 12, "已退款"),
    /** 部分退款 —— 仅退还部分金额 */
    PARTIALLY_REFUNDED("partially_refunded", 13, "部分退款"),
    /** 退款失败 —— 退款操作执行失败 */
    REFUND_FAILED("refund_failed", -10, "退款失败");

    /** 代码标识 */
    private final String key;
    /** 数值编码（正数=正向流程，负数=终态/异常，10+=退款流程） */
    private final Integer value;
    /** 中文描述 */
    private final String desc;
}
