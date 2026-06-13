-- V16: 账号上游协议成为唯一决策源，清理 OpenAI Responses 能力开关依赖。
--
-- 运行时不再读取 accounts.extra.openai_responses_supported / openai_responses_mode。
-- 这里把旧账号迁移为明确的 supported_protocols，之后新建/编辑账号应强制只保存一个协议。

-- OpenAI OAuth 固定走 Codex Responses。
UPDATE `accounts`
SET `supported_protocols` = JSON_ARRAY('responses')
WHERE `platform` = 'openai'
  AND `type` = 'oauth'
  AND (`supported_protocols` IS NULL OR JSON_LENGTH(`supported_protocols`) = 0)
  AND `deleted_at` IS NULL;

-- OpenAI API Key 旧配置显式降级 Chat Completions。
UPDATE `accounts`
SET `supported_protocols` = JSON_ARRAY('chat_completions')
WHERE `platform` = 'openai'
  AND `type` = 'api_key'
  AND (`supported_protocols` IS NULL OR JSON_LENGTH(`supported_protocols`) = 0)
  AND (
      JSON_UNQUOTE(JSON_EXTRACT(`extra`, '$.openai_responses_mode')) = 'force_chat_completions'
      OR JSON_EXTRACT(`extra`, '$.openai_responses_supported') = CAST('false' AS JSON)
  )
  AND `deleted_at` IS NULL;

-- 其余 OpenAI API Key 默认明确为 Responses，避免运行时再根据 extra 猜测。
UPDATE `accounts`
SET `supported_protocols` = JSON_ARRAY('responses')
WHERE `platform` = 'openai'
  AND `type` = 'api_key'
  AND (`supported_protocols` IS NULL OR JSON_LENGTH(`supported_protocols`) = 0)
  AND `deleted_at` IS NULL;

-- Anthropic 固定 Messages。
UPDATE `accounts`
SET `supported_protocols` = JSON_ARRAY('messages')
WHERE `platform` = 'anthropic'
  AND (`supported_protocols` IS NULL OR JSON_LENGTH(`supported_protocols`) = 0)
  AND `deleted_at` IS NULL;

-- Gemini 保留现有枚举协议标识。
UPDATE `accounts`
SET `supported_protocols` = JSON_ARRAY('gemini')
WHERE `platform` = 'gemini'
  AND (`supported_protocols` IS NULL OR JSON_LENGTH(`supported_protocols`) = 0)
  AND `deleted_at` IS NULL;
