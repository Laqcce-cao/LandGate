package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 余额变动业务类型 —— 标识余额变动由哪类业务动作触发。
 */
@Getter
@AllArgsConstructor
public enum BalanceTransactionType {

    /** 用户在线充值 */
    RECHARGE("用户在线充值"),
    /** 管理员线下收款充值 */
    ADMIN_RECHARGE("管理员线下收款充值"),
    /** 管理员赠送或补偿 */
    ADMIN_GRANT("管理员赠送/补偿"),
    /** 用户签到奖励 */
    CHECKIN_REWARD("签到奖励"),
    /** 退款返还 */
    REFUND("退款返还"),
    /** 管理员扣减余额 */
    ADMIN_DEDUCT("管理员扣减"),
    /** 系统余额修正 */
    ADJUSTMENT("系统修正");

    private final String desc;
}
