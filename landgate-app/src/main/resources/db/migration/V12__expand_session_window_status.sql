-- 扩展账号会话窗口状态字段，用于存储 OpenAI OAuth Codex 5h/7d 窗口 JSON。
ALTER TABLE accounts
    MODIFY COLUMN session_window_status TEXT;
