-- V2: 缓存创建 5m / 1h 粒度支持
-- usage_logs: 按 TTL 拆分的缓存创建 token 数与费用
-- model_prices: 缓存创建 5m / 1h 差异化定价 + 标记位

ALTER TABLE usage_logs
    ADD COLUMN cache_creation_5m_tokens INTEGER NOT NULL DEFAULT 0 AFTER cache_read_tokens,
    ADD COLUMN cache_creation_1h_tokens INTEGER NOT NULL DEFAULT 0 AFTER cache_creation_5m_tokens,
    ADD COLUMN cache_creation_5m_cost DECIMAL(20,10) NOT NULL DEFAULT 0 AFTER cache_read_cost,
    ADD COLUMN cache_creation_1h_cost DECIMAL(20,10) NOT NULL DEFAULT 0 AFTER cache_creation_5m_cost;

ALTER TABLE model_prices
    ADD COLUMN cache_write_5m_price    DECIMAL(20,10) NOT NULL DEFAULT 0 AFTER cache_read_price,
    ADD COLUMN cache_write_1h_price    DECIMAL(20,10) NOT NULL DEFAULT 0 AFTER cache_write_5m_price,
    ADD COLUMN supports_cache_breakdown TINYINT(1)  NOT NULL DEFAULT 0 AFTER cache_write_1h_price;
