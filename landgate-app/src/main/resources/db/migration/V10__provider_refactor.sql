-- ============================================
-- V10: Provider 架构重构（基于 sub2api 设计思想）
-- ============================================
-- 核心改动：
--   1. groups 表：新增 provider、supported_protocols、protocol_strategy
--   2. accounts 表：新增 supported_protocols、mixed_scheduling
--   3. model_prices 表：新增 billing_mode、wildcard_match、image_size
--   4. 新建 model_price_intervals 表（阶梯定价）
--   5. 数据迁移：合并 openai_responses → openai

-- ============================================
-- Part 1: groups 表改动
-- ============================================

ALTER TABLE `groups`
    ADD COLUMN `provider` VARCHAR(50) NOT NULL DEFAULT 'anthropic'
        COMMENT '提供商：anthropic, openai, gemini, antigravity'
        AFTER `name`;

ALTER TABLE `groups`
    ADD COLUMN `supported_protocols` JSON NULL
        COMMENT '支持的客户端 API 协议版本（JSON 数组），如 ["messages","chat_completions","responses"]。NULL 或空 = 不做限制'
        AFTER `provider`;

ALTER TABLE `groups`
    ADD COLUMN `protocol_strategy` VARCHAR(20) NOT NULL DEFAULT 'hub_and_spoke'
        COMMENT '协议转换策略：hub_and_spoke（允许 IR 转换）, native_only（不转换）'
        AFTER `supported_protocols`;

-- ============================================
-- Part 2: accounts 表改动
-- ============================================

ALTER TABLE `accounts`
    ADD COLUMN `supported_protocols` JSON NULL
        COMMENT '账户支持的上游 API 协议（JSON 数组），如 ["chat_completions","responses"]。NULL = 默认格式'
        AFTER `supported_models`;

ALTER TABLE `accounts`
    ADD COLUMN `mixed_scheduling` TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '允许跨 Provider 混合调度（如 Antigravity 账号混入 Anthropic 号池）'
        AFTER `supported_protocols`;

-- ============================================
-- Part 3: model_prices 表改动
-- ============================================

ALTER TABLE `model_prices`
    ADD COLUMN `billing_mode` VARCHAR(20) NOT NULL DEFAULT 'token'
        COMMENT '计费模式：token（按 token）, per_request（按次）, image（按图片）'
        AFTER `model`;

ALTER TABLE `model_prices`
    ADD COLUMN `wildcard_match` TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'model 字段是否为通配符模式（如 claude-opus-*）'
        AFTER `billing_mode`;

ALTER TABLE `model_prices`
    ADD COLUMN `image_size` VARCHAR(10) NULL
        COMMENT '图片尺寸（image 模式专用）：1K, 2K, 4K'
        AFTER `wildcard_match`;

-- ============================================
-- Part 4: 新建 model_price_intervals 表（阶梯定价）
-- ============================================

CREATE TABLE IF NOT EXISTS `model_price_intervals`
(
    `id`           BIGINT AUTO_INCREMENT PRIMARY KEY,
    `price_id`     BIGINT         NOT NULL COMMENT '关联 model_prices.id',
    `min_tokens`   BIGINT         NOT NULL DEFAULT 0 COMMENT '区间下限（含）',
    `max_tokens`   BIGINT         NULL COMMENT '区间上限（含），NULL = 无上限',
    `tier_label`   VARCHAR(50)    NULL COMMENT '阶梯标签（如图片 1K/2K/4K）',
    `input_price`  DECIMAL(20,10) NULL COMMENT '覆盖主表 input_price',
    `output_price` DECIMAL(20,10) NULL COMMENT '覆盖主表 output_price',
    `image_price`  DECIMAL(20,10) NULL COMMENT '覆盖主表图片单价',
    `created_at`   TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_interval_price` (`price_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT ='模型价格阶梯定价';

-- ============================================
-- Part 5: 数据迁移
-- ============================================

-- 5.1 合并 OPENAI_RESPONSES 账户为 OPENAI
--     通过 supported_protocols 区分原 Responses API 账户
UPDATE `accounts`
SET `platform`            = 'openai',
    `supported_protocols` = JSON_ARRAY('responses')
WHERE `platform` = 'openai_responses'
  AND `deleted_at` IS NULL;

-- 5.2 普通 OpenAI 账户默认标注支持 chat_completions
UPDATE `accounts`
SET `supported_protocols` = JSON_ARRAY('chat_completions')
WHERE `platform` = 'openai'
  AND `supported_protocols` IS NULL
  AND `deleted_at` IS NULL;

-- 5.3 Anthropic 账户默认标注支持 messages
UPDATE `accounts`
SET `supported_protocols` = JSON_ARRAY('messages')
WHERE `platform` = 'anthropic'
  AND `supported_protocols` IS NULL
  AND `deleted_at` IS NULL;

-- 5.4 Gemini 账户标注
UPDATE `accounts`
SET `supported_protocols` = JSON_ARRAY('gemini')
WHERE `platform` = 'gemini'
  AND `supported_protocols` IS NULL
  AND `deleted_at` IS NULL;

-- 5.5 迁移 groups 的 provider：根据关联账号推断（取该 group 下最多的 platform）
--     注意：如果一个 group 下有多种 platform 的账号，取数量最多的
UPDATE `groups` g
    INNER JOIN (
    SELECT ag.group_id,
           a.platform,
           COUNT(*)                                      AS cnt,
           ROW_NUMBER() OVER (PARTITION BY ag.group_id ORDER BY COUNT(*) DESC) AS rn
    FROM `account_groups` ag
             INNER JOIN `accounts` a ON a.id = ag.account_id AND a.deleted_at IS NULL
    WHERE ag.group_id IS NOT NULL
    GROUP BY ag.group_id, a.platform
) t ON g.id = t.group_id AND t.rn = 1
SET g.provider = t.platform
WHERE g.deleted_at IS NULL;
