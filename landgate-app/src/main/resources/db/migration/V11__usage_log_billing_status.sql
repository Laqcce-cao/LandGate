-- ============================================
-- V11: 用量日志计费状态与对账字段
-- ============================================
-- usage_logs 作为扣费事实来源，记录扣费是否完成以及失败原因。

ALTER TABLE `usage_logs`
    ADD COLUMN `billing_status` VARCHAR(20) NOT NULL DEFAULT 'DEDUCTED'
        COMMENT '计费状态：PENDING(待扣费), SETTLING(扣费中), DEDUCTED(已扣费), FAILED(扣费失败)' AFTER `image_size`,
    ADD COLUMN `billing_error` VARCHAR(500) NULL
        COMMENT '扣费失败原因' AFTER `billing_status`,
    ADD COLUMN `client_disconnected` TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '流式响应期间客户端是否断开' AFTER `billing_error`;

CREATE INDEX `idx_usage_logs_billing_status_created`
    ON `usage_logs` (`billing_status`, `created_at`);
