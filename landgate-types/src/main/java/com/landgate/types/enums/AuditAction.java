package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 支付审计日志操作枚举 —— 对应 PaymentAuditLog 的 ACTION_* 常量。
 * <p>
 * 记录支付流程中每个关键操作节点，用于审计追溯。
 */
@Getter
@AllArgsConstructor
public enum AuditAction {

    /** 订单创建 —— 用户发起新的支付订单 */
    ORDER_CREATED("order_created", 1, "订单创建"),
    /** 订单支付成功 —— 用户完成付款 */
    ORDER_PAID("order_paid", 2, "订单支付成功"),
    /** 充值到账 —— 余额已充入用户账户 */
    RECHARGE_SUCCESS("recharge_success", 3, "充值到账"),
    /** 退款成功 —— 退款已退回用户支付渠道 */
    REFUND_SUCCESS("refund_success", 4, "退款成功"),
    /** 退款失败 —— 退款操作执行失败 */
    REFUND_FAILED("refund_failed", 5, "退款失败"),
    /** 退款网关失败 —— 支付网关拒绝退款请求 */
    REFUND_GATEWAY_FAILED("refund_gateway_failed", 6, "退款网关失败"),
    /** 订单取消 —— 用户或系统取消订单 */
    ORDER_CANCELLED("order_cancelled", 7, "订单取消"),
    /** 订单过期 —— 订单超过支付有效期自动关闭 */
    ORDER_EXPIRED("order_expired", 8, "订单过期"),
    /** 订单完成 —— 订单全部流程处理完毕 */
    ORDER_COMPLETED("order_completed", 9, "订单完成"),
    /** 订单失败 —— 订单处理失败 */
    ORDER_FAILED("order_failed", 10, "订单失败"),
    /** 退款申请 —— 用户提交退款请求 */
    REFUND_REQUESTED("refund_requested", 11, "退款申请"),
    /** 退款申请通过 —— 管理员批准退款申请 */
    REFUND_REQUEST_APPROVED("refund_request_approved", 12, "退款申请通过"),
    /** 退款申请拒绝 —— 管理员拒绝退款申请 */
    REFUND_REQUEST_REJECTED("refund_request_rejected", 13, "退款申请拒绝"),
    /** 支付回调接收 —— 接收到支付网关的异步通知 */
    PAYMENT_NOTIFICATION_RECEIVED("payment_notification_received", 14, "支付回调接收"),
    /** 支付确认 —— 人工或系统确认支付已到账 */
    PAYMENT_CONFIRMED("payment_confirmed", 15, "支付确认");

    /** 代码标识 */
    private final String key;
    /** 数值编码 */
    private final Integer value;
    /** 中文描述 */
    private final String desc;
}
