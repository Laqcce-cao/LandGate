-- V8: 添加 model_prices 表的唯一约束（V6 删除 group_id 时隐式丢失了唯一索引）
ALTER TABLE model_prices ADD UNIQUE INDEX idx_model_price_model_platform (model, platform);
