-- 用户余额变动记录表：记录充值、赠送、签到、退款、扣减、调整等低频余额变动，不记录 API 高频消费。
CREATE TABLE IF NOT EXISTS balance_transactions (
    id BIGINT AUTO_INCREMENT COMMENT '主键 ID',
    user_id BIGINT NOT NULL COMMENT '用户 ID',

    transaction_type VARCHAR(50) NOT NULL COMMENT '余额变动业务类型：RECHARGE 用户在线充值、ADMIN_RECHARGE 管理员线下充值、ADMIN_GRANT 管理员赠送、CHECKIN_REWARD 签到奖励、REFUND 退款返还、ADMIN_DEDUCT 管理员扣减、ADJUSTMENT 系统修正',
    funding_type VARCHAR(50) NOT NULL COMMENT '资金性质：PAID 付费、GIFT 赠送、REFUND 退款、DEDUCT 扣减、ADJUSTMENT 调整',

    amount DECIMAL(20,8) NOT NULL COMMENT '用户余额变动金额，正数增加，负数扣减',
    cash_income_amount DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '真实现金收入金额，收款为正，退款为负，不涉及现金为 0',
    balance_before DECIMAL(20,8) NULL COMMENT '余额变动前金额，由 Redis 原子调整返回',
    balance_after DECIMAL(20,8) NULL COMMENT '余额变动后金额，由 Redis 原子调整返回',

    source_type VARCHAR(50) NOT NULL COMMENT '来源业务类型，如 PAYMENT_ORDER、CHECKIN、ADMIN_OPERATION、SYSTEM',
    source_id VARCHAR(128) NOT NULL COMMENT '来源业务 ID，字符串格式，配合 source_type 用于幂等和追溯',
    operator_type VARCHAR(50) NOT NULL COMMENT '操作人类型：USER 用户、ADMIN 管理员、SYSTEM 系统、PAYMENT_PROVIDER 支付渠道',
    operator_id VARCHAR(128) NULL COMMENT '操作人 ID，字符串格式，例如用户 ID、管理员 ID、system、支付渠道标识',

    remark VARCHAR(255) NULL COMMENT '备注说明，用于前端展示和管理员审计',
    metadata JSON NULL COMMENT '扩展信息 JSON，存放签到天数、活动信息、支付渠道等非固定字段',

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '处理状态：PENDING 待处理、COMPLETED 已完成、FAILED 失败',
    failure_reason TEXT NULL COMMENT '失败原因，Redis 调整失败等异常信息',
    completed_at TIMESTAMP NULL COMMENT '余额调整成功时间',

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_bt_source_type (source_type, source_id, transaction_type),
    INDEX idx_bt_user_created (user_id, created_at DESC, id DESC),
    INDEX idx_bt_type_created (transaction_type, created_at),
    INDEX idx_bt_funding_created (funding_type, created_at),
    INDEX idx_bt_status_created (status, created_at),
    INDEX idx_bt_source (source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户余额变动记录表，记录低频余额变动及其来源，不记录 API 高频消费';
