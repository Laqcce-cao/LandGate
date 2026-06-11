package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 余额资金性质 —— 用于区分余额来源或调整的资金属性。
 */
@Getter
@AllArgsConstructor
public enum BalanceFundingType {

    /** 付费型余额，如在线充值或线下收款充值 */
    PAID("付费型余额"),
    /** 赠送型余额，如管理员赠送、签到奖励 */
    GIFT("赠送型余额"),
    /** 退款型余额 */
    REFUND("退款型余额"),
    /** 扣减型余额 */
    DEDUCT("扣减型余额"),
    /** 调整型余额 */
    ADJUSTMENT("调整型余额");

    private final String desc;
}
