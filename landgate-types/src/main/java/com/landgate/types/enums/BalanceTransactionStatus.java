package com.landgate.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 余额流水处理状态 —— 标识低频余额变动是否已在 Redis 运行态余额中生效。
 */
@Getter
@AllArgsConstructor
public enum BalanceTransactionStatus {

    /** 已创建流水，等待执行 Redis 余额调整 */
    PENDING("待处理"),
    /** Redis 余额调整成功，余额变动已生效 */
    COMPLETED("已完成"),
    /** Redis 余额调整失败，可按业务来源重试 */
    FAILED("失败");

    private final String desc;
}
