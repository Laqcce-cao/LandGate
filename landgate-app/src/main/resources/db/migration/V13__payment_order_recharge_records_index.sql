-- 用户侧充值记录查询索引
-- 匹配查询：WHERE user_id = ? AND order_type IN ('BALANCE', 'balance') ORDER BY created_at DESC, id DESC
CREATE INDEX idx_po_user_type_created_id
    ON payment_orders (user_id, order_type, created_at DESC, id DESC);
