-- 将历史用户邮箱统一规范为小写，匹配主流邮箱服务大小写不敏感的使用习惯。
-- users.email_unique 是基于 email 的生成列，更新 email 后唯一索引会自动使用小写值。
UPDATE users
SET email = LOWER(TRIM(email))
WHERE email <> LOWER(TRIM(email));
