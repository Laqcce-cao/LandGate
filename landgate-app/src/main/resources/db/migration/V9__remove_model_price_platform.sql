-- V9: 移除 model_prices 表的 platform 字段，model 名称本身即可唯一标识一个模型的价格
ALTER TABLE model_prices DROP INDEX idx_model_price_model_platform;
ALTER TABLE model_prices DROP COLUMN platform;
ALTER TABLE model_prices ADD UNIQUE INDEX idx_model_price_model (model);
