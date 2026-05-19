-- =============================================================================
-- LandGate Baseline Schema (consolidated V1-V18)
-- Single migration replacing all incremental Flyway scripts.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. users (V1 + V18 defaults)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    email_verified  TINYINT(1) NOT NULL DEFAULT 0,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(20)  NOT NULL DEFAULT 'user',
    balance         DECIMAL(20,8) NOT NULL DEFAULT 0,
    concurrency     INTEGER NOT NULL DEFAULT 5,
    status          VARCHAR(20)  NOT NULL DEFAULT 'active',
    username        VARCHAR(100) NOT NULL DEFAULT '',
    notes           TEXT NULL DEFAULT NULL,

    totp_secret_encrypted TEXT,
    totp_enabled     TINYINT(1) NOT NULL DEFAULT 0,
    totp_enabled_at  TIMESTAMP NULL,

    signup_source    VARCHAR(20) NOT NULL DEFAULT 'email',
    last_login_at    TIMESTAMP NULL,
    last_active_at   TIMESTAMP NULL,

    balance_notify_enabled        TINYINT(1) NOT NULL DEFAULT 1,
    balance_notify_threshold_type VARCHAR(20) NOT NULL DEFAULT 'fixed',
    balance_notify_threshold      DECIMAL(20,8),
    balance_notify_extra_emails   TEXT NULL DEFAULT NULL,
    total_recharged  DECIMAL(20,8) NOT NULL DEFAULT 0,

    token_version    BIGINT NOT NULL DEFAULT 0,
    rpm_limit        INTEGER NOT NULL DEFAULT 0,

    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at       TIMESTAMP NULL,

    email_unique     VARCHAR(255) GENERATED ALWAYS AS (IF(deleted_at IS NULL, email, NULL)) VIRTUAL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE UNIQUE INDEX idx_users_email_active ON users (email_unique);
CREATE INDEX idx_users_status ON users (status);
CREATE INDEX idx_users_deleted_at ON users (deleted_at);

-- ---------------------------------------------------------------------------
-- 2. api_keys (V2)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS api_keys (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    `key`           VARCHAR(128) NOT NULL,
    name            VARCHAR(100) NOT NULL,
    group_id        BIGINT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    last_used_at    TIMESTAMP NULL,

    ip_whitelist    JSON DEFAULT ('[]'),
    ip_blacklist    JSON DEFAULT ('[]'),

    quota           DECIMAL(20,8) NOT NULL DEFAULT 0,
    quota_used      DECIMAL(20,8) NOT NULL DEFAULT 0,
    expires_at      TIMESTAMP NULL,

    rate_limit_5h   DECIMAL(20,8) NOT NULL DEFAULT 0,
    rate_limit_1d   DECIMAL(20,8) NOT NULL DEFAULT 0,
    rate_limit_7d   DECIMAL(20,8) NOT NULL DEFAULT 0,

    usage_5h        DECIMAL(20,8) NOT NULL DEFAULT 0,
    usage_1d        DECIMAL(20,8) NOT NULL DEFAULT 0,
    usage_7d        DECIMAL(20,8) NOT NULL DEFAULT 0,

    window_5h_start TIMESTAMP NULL,
    window_1d_start TIMESTAMP NULL,
    window_7d_start TIMESTAMP NULL,

    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      TIMESTAMP NULL,

    key_unique      VARCHAR(128) GENERATED ALWAYS AS (IF(deleted_at IS NULL, `key`, NULL)) VIRTUAL,

    CONSTRAINT fk_api_keys_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE UNIQUE INDEX idx_api_keys_key_active ON api_keys (key_unique);
CREATE INDEX idx_api_keys_user_id ON api_keys (user_id);
CREATE INDEX idx_api_keys_group_id ON api_keys (group_id);
CREATE INDEX idx_api_keys_status ON api_keys (status);
CREATE INDEX idx_api_keys_deleted_at ON api_keys (deleted_at);
CREATE INDEX idx_api_keys_last_used_at ON api_keys (last_used_at);
CREATE INDEX idx_api_keys_expires_at ON api_keys (expires_at);

-- ---------------------------------------------------------------------------
-- 3. accounts (V3)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS accounts (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    notes           TEXT,

    platform        VARCHAR(50)  NOT NULL,
    type            VARCHAR(20)  NOT NULL,

    credentials     JSON NOT NULL DEFAULT ('{}'),
    extra           JSON NOT NULL DEFAULT ('{}'),

    proxy_id        BIGINT,

    concurrency     INTEGER NOT NULL DEFAULT 3,
    load_factor     INTEGER,
    priority        INTEGER NOT NULL DEFAULT 50,
    rate_multiplier DECIMAL(10,4) NOT NULL DEFAULT 1.0,

    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    error_message   TEXT,

    last_used_at    TIMESTAMP NULL,
    expires_at      TIMESTAMP NULL,
    auto_pause_on_expired TINYINT(1) NOT NULL DEFAULT 1,

    schedulable     TINYINT(1) NOT NULL DEFAULT 1,

    rate_limited_at     TIMESTAMP NULL,
    rate_limit_reset_at TIMESTAMP NULL,
    overload_until      TIMESTAMP NULL,

    temp_unschedulable_until   TIMESTAMP NULL,
    temp_unschedulable_reason  TEXT,

    session_window_start  TIMESTAMP NULL,
    session_window_end    TIMESTAMP NULL,
    session_window_status VARCHAR(20),

    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      TIMESTAMP NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_accounts_platform ON accounts (platform);
CREATE INDEX idx_accounts_type ON accounts (type);
CREATE INDEX idx_accounts_status ON accounts (status);
CREATE INDEX idx_accounts_proxy_id ON accounts (proxy_id);
CREATE INDEX idx_accounts_priority ON accounts (priority);
CREATE INDEX idx_accounts_last_used_at ON accounts (last_used_at);
CREATE INDEX idx_accounts_schedulable ON accounts (schedulable);
CREATE INDEX idx_accounts_rate_limited_at ON accounts (rate_limited_at);
CREATE INDEX idx_accounts_rate_limit_reset_at ON accounts (rate_limit_reset_at);
CREATE INDEX idx_accounts_overload_until ON accounts (overload_until);
CREATE INDEX idx_accounts_platform_priority ON accounts (platform, priority);
CREATE INDEX idx_accounts_priority_status ON accounts (priority, status);
CREATE INDEX idx_accounts_deleted_at ON accounts (deleted_at);

-- ---------------------------------------------------------------------------
-- 4. proxies (V4)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS proxies (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    protocol    VARCHAR(20)  NOT NULL,
    host        VARCHAR(255) NOT NULL,
    port        INTEGER NOT NULL,
    username    VARCHAR(100),
    password    VARCHAR(100),
    status      VARCHAR(20) NOT NULL DEFAULT 'active',

    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at  TIMESTAMP NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_proxies_status ON proxies (status);
CREATE INDEX idx_proxies_deleted_at ON proxies (deleted_at);

-- ---------------------------------------------------------------------------
-- 5. groups (V5)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `groups` (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    description     TEXT,
    rate_multiplier DECIMAL(10,4) NOT NULL DEFAULT 1.0,
    is_exclusive    TINYINT(1) NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'active',

    platform            VARCHAR(50) NOT NULL DEFAULT 'anthropic',
    subscription_type   VARCHAR(20) NOT NULL DEFAULT 'standard',
    daily_limit_usd     DECIMAL(20,8),
    weekly_limit_usd    DECIMAL(20,8),
    monthly_limit_usd   DECIMAL(20,8),
    default_validity_days INTEGER NOT NULL DEFAULT 30,

    allow_image_generation   TINYINT(1) NOT NULL DEFAULT 0,
    image_rate_independent   TINYINT(1) NOT NULL DEFAULT 0,
    image_rate_multiplier    DECIMAL(10,4) NOT NULL DEFAULT 1.0,
    image_price_1k           DECIMAL(20,8),
    image_price_2k           DECIMAL(20,8),
    image_price_4k           DECIMAL(20,8),

    claude_code_only                    TINYINT(1) NOT NULL DEFAULT 0,
    fallback_group_id                   BIGINT,
    fallback_group_id_on_invalid_request BIGINT,

    model_routing           JSON DEFAULT ('{}'),
    model_routing_enabled   TINYINT(1) NOT NULL DEFAULT 0,

    mcp_xml_inject          TINYINT(1) NOT NULL DEFAULT 1,

    supported_model_scopes  JSON DEFAULT ('["claude", "gemini_text", "gemini_image"]'),

    sort_order              INTEGER NOT NULL DEFAULT 0,

    allow_messages_dispatch         TINYINT(1) NOT NULL DEFAULT 0,
    require_oauth_only              TINYINT(1) NOT NULL DEFAULT 0,
    require_privacy_set             TINYINT(1) NOT NULL DEFAULT 0,
    default_mapped_model            VARCHAR(100) NOT NULL DEFAULT '',
    messages_dispatch_model_config  JSON DEFAULT ('{}'),

    rpm_limit               INTEGER NOT NULL DEFAULT 0,
    excluded_models         TEXT DEFAULT NULL,

    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      TIMESTAMP NULL,

    name_unique     VARCHAR(100) GENERATED ALWAYS AS (IF(deleted_at IS NULL, name, NULL)) VIRTUAL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE UNIQUE INDEX idx_groups_name_active ON `groups` (name_unique);
CREATE INDEX idx_groups_status ON `groups` (status);
CREATE INDEX idx_groups_platform ON `groups` (platform);
CREATE INDEX idx_groups_subscription_type ON `groups` (subscription_type);
CREATE INDEX idx_groups_is_exclusive ON `groups` (is_exclusive);
CREATE INDEX idx_groups_deleted_at ON `groups` (deleted_at);
CREATE INDEX idx_groups_sort_order ON `groups` (sort_order);

-- ---------------------------------------------------------------------------
-- 6. account_groups (V6)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account_groups (
    account_id  BIGINT NOT NULL,
    group_id    BIGINT NOT NULL,
    priority    INTEGER NOT NULL DEFAULT 50,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (account_id, group_id),
    CONSTRAINT fk_account_groups_account FOREIGN KEY (account_id) REFERENCES accounts(id),
    CONSTRAINT fk_account_groups_group FOREIGN KEY (group_id) REFERENCES `groups`(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_account_groups_group_id ON account_groups (group_id);
CREATE INDEX idx_account_groups_priority ON account_groups (priority);

-- ---------------------------------------------------------------------------
-- 7. user_allowed_groups (V7)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_allowed_groups (
    user_id     BIGINT NOT NULL,
    group_id    BIGINT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id, group_id),
    CONSTRAINT fk_user_allowed_groups_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_user_allowed_groups_group FOREIGN KEY (group_id) REFERENCES `groups`(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_user_allowed_groups_group_id ON user_allowed_groups (group_id);

-- ---------------------------------------------------------------------------
-- 8. usage_logs (V8 + V16 platform column)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS usage_logs (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,

    user_id         BIGINT NOT NULL,
    api_key_id      BIGINT NOT NULL,
    account_id      BIGINT NOT NULL,
    group_id        BIGINT,
    subscription_id BIGINT,

    request_id      VARCHAR(64) NOT NULL,
    model           VARCHAR(100) NOT NULL,
    platform        VARCHAR(20) NULL,
    requested_model VARCHAR(100),
    upstream_model  VARCHAR(100),
    billing_mode    VARCHAR(20) DEFAULT 'token',

    input_tokens            INTEGER NOT NULL DEFAULT 0,
    output_tokens           INTEGER NOT NULL DEFAULT 0,
    cache_creation_tokens   INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens       INTEGER NOT NULL DEFAULT 0,

    input_cost          DECIMAL(20,10) NOT NULL DEFAULT 0,
    output_cost         DECIMAL(20,10) NOT NULL DEFAULT 0,
    cache_creation_cost DECIMAL(20,10) NOT NULL DEFAULT 0,
    cache_read_cost     DECIMAL(20,10) NOT NULL DEFAULT 0,
    total_cost          DECIMAL(20,10) NOT NULL DEFAULT 0,
    actual_cost         DECIMAL(20,10) NOT NULL DEFAULT 0,

    rate_multiplier         DECIMAL(10,4) NOT NULL DEFAULT 1.0,
    account_rate_multiplier DECIMAL(10,4),

    stream          TINYINT(1) NOT NULL DEFAULT 0,
    duration_ms     INTEGER,
    first_token_ms  INTEGER,

    user_agent      VARCHAR(512),
    ip_address      VARCHAR(45),

    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_usage_logs_user_id ON usage_logs (user_id);
CREATE INDEX idx_usage_logs_api_key_id ON usage_logs (api_key_id);
CREATE INDEX idx_usage_logs_account_id ON usage_logs (account_id);
CREATE INDEX idx_usage_logs_group_id ON usage_logs (group_id);
CREATE INDEX idx_usage_logs_created_at ON usage_logs (created_at);
CREATE INDEX idx_usage_logs_model ON usage_logs (model);
CREATE INDEX idx_usage_logs_platform ON usage_logs (platform);
CREATE INDEX idx_usage_logs_user_created ON usage_logs (user_id, created_at);
CREATE INDEX idx_usage_logs_key_created ON usage_logs (api_key_id, created_at);
CREATE INDEX idx_usage_logs_group_created ON usage_logs (group_id, created_at);

-- ---------------------------------------------------------------------------
-- 9. payment_provider_instances (V9)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_provider_instances (
    id               BIGINT        AUTO_INCREMENT PRIMARY KEY,
    provider_key     VARCHAR(30)   NOT NULL COMMENT '提供商标识',
    name             VARCHAR(100)  NOT NULL COMMENT '名称',
    config           TEXT          NOT NULL COMMENT 'AES-256-GCM 加密后的提供商配置 JSON',
    supported_types  VARCHAR(200)  NOT NULL DEFAULT '' COMMENT '支持的支付子类型',
    enabled          TINYINT(1)    NOT NULL DEFAULT 1,
    payment_mode     VARCHAR(20)   NOT NULL DEFAULT 'qrcode',
    sort_order       INT           NOT NULL DEFAULT 0,
    limits           TEXT          NULL COMMENT '限额配置 JSON',
    refund_enabled   TINYINT(1)    NOT NULL DEFAULT 1,
    allow_user_refund TINYINT(1)   NOT NULL DEFAULT 0,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at       TIMESTAMP     NULL DEFAULT NULL,

    INDEX idx_pi_provider_key (provider_key),
    INDEX idx_pi_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 10. payment_orders (V10)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_orders (
    id                     BIGINT          AUTO_INCREMENT PRIMARY KEY,
    user_id                BIGINT          NOT NULL,
    user_email             VARCHAR(255)    NOT NULL,
    user_name              VARCHAR(100)    NOT NULL DEFAULT '',
    user_notes             TEXT            NULL,
    amount                 DECIMAL(20,2)   NOT NULL,
    pay_amount             DECIMAL(20,2)   NOT NULL,
    fee_rate               DECIMAL(10,4)   NOT NULL DEFAULT 0,
    recharge_code          VARCHAR(64)     NULL,
    out_trade_no           VARCHAR(64)     NULL,
    payment_type           VARCHAR(30)     NOT NULL,
    payment_trade_no       VARCHAR(128)    NULL,
    pay_url                TEXT            NULL,
    qr_code                TEXT            NULL,
    qr_code_img            TEXT            NULL,
    order_type             VARCHAR(20)     NOT NULL DEFAULT 'balance',
    plan_id                BIGINT          NULL,
    subscription_group_id  BIGINT          NULL,
    subscription_days      INT             NULL,
    provider_instance_id   VARCHAR(64)     NULL,
    provider_key           VARCHAR(30)     NULL,
    provider_snapshot      JSON            NULL,
    status                 VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    refund_amount          DECIMAL(20,2)   NOT NULL DEFAULT 0,
    refund_reason          TEXT            NULL,
    refund_at              TIMESTAMP       NULL,
    force_refund           TINYINT(1)      NOT NULL DEFAULT 0,
    refund_requested_at    TIMESTAMP       NULL,
    refund_request_reason  TEXT            NULL,
    refund_requested_by    VARCHAR(20)     NULL,
    expires_at             TIMESTAMP       NOT NULL,
    paid_at                TIMESTAMP       NULL,
    completed_at           TIMESTAMP       NULL,
    failed_at              TIMESTAMP       NULL,
    failed_reason          TEXT            NULL,
    client_ip              VARCHAR(50)     NOT NULL DEFAULT '',
    src_host               VARCHAR(255)    NOT NULL DEFAULT '',
    src_url                TEXT            NULL,
    created_at             TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at             TIMESTAMP       NULL DEFAULT NULL,

    UNIQUE INDEX idx_po_out_trade_no (out_trade_no),
    INDEX idx_po_user_id (user_id),
    INDEX idx_po_status (status),
    INDEX idx_po_expires_at (expires_at),
    INDEX idx_po_created_at (created_at),
    INDEX idx_po_paid_at (paid_at),
    INDEX idx_po_payment_type_paid (payment_type, paid_at),
    INDEX idx_po_order_type (order_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 11. payment_audit_logs (V11)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS payment_audit_logs (
    id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
    order_id   VARCHAR(64)  NOT NULL,
    action     VARCHAR(50)  NOT NULL,
    detail     TEXT         NULL,
    operator   VARCHAR(100) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_pal_order_id (order_id),
    INDEX idx_pal_action (action),
    INDEX idx_pal_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 12. announcements (V12)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS announcements (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    content     TEXT         NOT NULL,
    type        VARCHAR(20)  NOT NULL DEFAULT 'info',
    published   TINYINT(1)   NOT NULL DEFAULT 0,
    publish_at  TIMESTAMP    NULL,
    expires_at  TIMESTAMP    NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_by  BIGINT       NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at  TIMESTAMP    NULL DEFAULT NULL,

    INDEX idx_ann_published (published),
    INDEX idx_ann_expires (expires_at),
    INDEX idx_ann_sort (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 13. redeem_codes (V13)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS redeem_codes (
    id               BIGINT         AUTO_INCREMENT PRIMARY KEY,
    code             VARCHAR(64)    NOT NULL,
    type             VARCHAR(20)    NOT NULL DEFAULT 'balance',
    amount           DECIMAL(20,2)  NULL,
    group_id         BIGINT         NULL,
    subscription_days INT           NULL,
    max_uses         INT            NOT NULL DEFAULT 1,
    used_count       INT            NOT NULL DEFAULT 0,
    bound_user_id    BIGINT         NULL,
    enabled          TINYINT(1)     NOT NULL DEFAULT 1,
    expires_at       TIMESTAMP      NULL,
    created_by       BIGINT         NULL,
    notes            TEXT           NULL,
    created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at       TIMESTAMP      NULL DEFAULT NULL,

    UNIQUE INDEX idx_rc_code (code),
    INDEX idx_rc_enabled (enabled),
    INDEX idx_rc_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 14. promo_codes (V14)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS promo_codes (
    id                  BIGINT         AUTO_INCREMENT PRIMARY KEY,
    code                VARCHAR(64)    NOT NULL,
    discount_type       VARCHAR(20)    NOT NULL DEFAULT 'fixed',
    discount_value      DECIMAL(20,2)  NOT NULL,
    min_order_amount    DECIMAL(20,2)  NOT NULL DEFAULT 0,
    max_discount_amount DECIMAL(20,2)  NOT NULL DEFAULT 0,
    max_uses            INT            NOT NULL DEFAULT 0,
    used_count          INT            NOT NULL DEFAULT 0,
    max_uses_per_user   INT            NOT NULL DEFAULT 1,
    enabled             TINYINT(1)     NOT NULL DEFAULT 1,
    starts_at           TIMESTAMP      NULL,
    expires_at          TIMESTAMP      NULL,
    created_by          BIGINT         NULL,
    notes               TEXT           NULL,
    created_at          TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at          TIMESTAMP      NULL DEFAULT NULL,

    UNIQUE INDEX idx_pc_code (code),
    INDEX idx_pc_enabled (enabled),
    INDEX idx_pc_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 15. model_prices (V15 + V17 per-million-token prices)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS model_prices (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,

    model            VARCHAR(100) NOT NULL COMMENT 'Model identifier',
    platform         VARCHAR(20)  NOT NULL COMMENT 'Platform: ANTHROPIC, OPENAI, GEMINI',

    -- Price per MILLION tokens (USD)
    input_price       DECIMAL(20,10) NOT NULL DEFAULT 0 COMMENT 'Price per million input tokens (USD)',
    output_price      DECIMAL(20,10) NOT NULL DEFAULT 0 COMMENT 'Price per million output tokens (USD)',
    cache_write_price DECIMAL(20,10) NOT NULL DEFAULT 0 COMMENT 'Price per million cache write tokens (USD)',
    cache_read_price  DECIMAL(20,10) NOT NULL DEFAULT 0 COMMENT 'Price per million cache read tokens (USD)',

    group_id          BIGINT NULL COMMENT 'NULL = global default',

    enabled           TINYINT(1) NOT NULL DEFAULT 1,
    notes             VARCHAR(255),

    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at   TIMESTAMP NULL DEFAULT NULL,

    UNIQUE INDEX idx_mdl_price_model_group (model, group_id),
    INDEX idx_mdl_price_platform (platform),
    INDEX idx_mdl_price_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed data: Anthropic (per-M-token prices)
INSERT INTO model_prices (model, platform, input_price, output_price, cache_write_price, cache_read_price) VALUES
('claude-opus-4-20250514',    'ANTHROPIC', 15, 75, 18.75, 1.5),
('claude-sonnet-4-20250514',  'ANTHROPIC', 3,  15, 3.75,  0.3),
('claude-opus-4-5-20251101',  'ANTHROPIC', 5,  25, 6.25,  0.5),
('claude-sonnet-4-5-20250929','ANTHROPIC', 3,  15, 3.75,  0.3),
('claude-haiku-4-5-20251001', 'ANTHROPIC', 1,  5,  1.25,  0.1);

-- Seed data: OpenAI (per-M-token prices)
INSERT INTO model_prices (model, platform, input_price, output_price) VALUES
('gpt-4o',              'OPENAI', 2.5,   10),
('gpt-4o-mini',         'OPENAI', 0.15,  0.6),
('gpt-4-turbo',         'OPENAI', 10,    30),
('gpt-4',               'OPENAI', 30,    60),
('gpt-3.5-turbo',       'OPENAI', 0.5,   1.5),
('o1',                  'OPENAI', 15,    60),
('o1-mini',             'OPENAI', 3,     12),
('o3-mini',             'OPENAI', 1.1,   4.4);

-- Seed data: Gemini (per-M-token prices)
INSERT INTO model_prices (model, platform, input_price, output_price) VALUES
('gemini-2.5-flash',    'GEMINI', 0.15,  0.6),
('gemini-2.5-pro',      'GEMINI', 1.25,  10),
('gemini-2.0-flash',    'GEMINI', 0.1,   0.4),
('gemini-1.5-flash',    'GEMINI', 0.075, 0.3),
('gemini-1.5-pro',      'GEMINI', 1.25,  5);
