-- 用户签到记录表：记录每日签到、连续天数和签到奖励发放状态。
CREATE TABLE IF NOT EXISTS user_checkins (
    id BIGINT AUTO_INCREMENT COMMENT '主键 ID',
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    sign_date DATE NOT NULL COMMENT '签到日期，按北京时间 Asia/Shanghai 计算',
    streak_days INT NOT NULL DEFAULT 1 COMMENT '连续签到天数，断签后从 1 开始',
    reward_amount DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '本次签到奖励金额',
    balance_transaction_id BIGINT NULL COMMENT '关联的余额流水 ID，对应 balance_transactions.id',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '签到状态：PENDING 待发放、COMPLETED 已完成、FAILED 失败',
    failure_reason TEXT NULL COMMENT '失败原因，奖励发放失败时记录',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_checkin_user_date (user_id, sign_date),
    INDEX idx_checkin_user_date (user_id, sign_date DESC),
    INDEX idx_checkin_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户签到记录表，记录每日签到、连续天数和签到奖励发放状态';
