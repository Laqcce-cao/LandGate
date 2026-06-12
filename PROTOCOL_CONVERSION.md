# 网关协议转换分析

本文档记录当前纳入范围的网关协议转换模型：
Anthropic Messages、OpenAI Chat Completions 和 OpenAI Responses。Gemini 有意不包含在本次分析范围内。

官方参考：

- Anthropic Messages API: https://docs.anthropic.com/en/api/messages
- Anthropic Messages streaming: https://docs.anthropic.com/en/api/messages-streaming
- OpenAI Chat Completions API: https://developers.openai.com/api/docs/api-reference/chat/create
- OpenAI Responses API: https://developers.openai.com/api/docs/api-reference/responses/create
- OpenAI API authentication: https://developers.openai.com/api/docs/api-reference/authentication
- OpenAI Codex authentication: https://developers.openai.com/codex/administration/authentication

## 核心模型

LandGate 使用 OpenAI Responses 结构作为内部 IR（中间表示）。这是合适的中心协议，因为 Responses 能表达比 Chat 或 Anthropic 更完整的跨协议能力：

- 带有类型化内容分片的 message output item
- function call output item
- function call result input item
- reasoning output item
- refusal 内容
- usage 明细，包括 cached tokens
- output item 和 content delta 的流式生命周期事件

Chat Completions 和 Anthropic Messages 是能力更窄的边缘协议。转换器只应转换协议结构，不应推断模型能力，也不应静默改变请求意图。

## 认证与路由

OpenAI 公共 API Key 账号使用 OpenAI 公共 API 端点：

- 支持 Responses 的请求路由到 `/v1/responses`。
- 其他请求路由到 `/v1/chat/completions`。
- 公共 API Key 认证是普通的 `Authorization: Bearer <api_key>` 请求。
- 公共 API Key 请求应尽量保持官方公共协议语义。比如客户端显式传入的 `store` 不应在协议转换层被改写；未显式传入时，网关默认写入 `store=false`，避免无意持久化。

OpenAI OAuth 账号在本项目中不同于公共 API Key 账号：

- OAuth 账号路由到 ChatGPT Codex 内部 Responses 端点。
- 请求体会被规范化，以兼容 Codex 内部端点。
- 同样使用 `Authorization: Bearer <access_token>` 这种请求头形式，但 token 的签发方、刷新生命周期、端点以及可接受的请求字段都不同于 API Key。
- OAuth 的 401 可以通过刷新 token 恢复；API Key 的 401 被视为凭证失败。
- OAuth Codex 普通 Responses 路径强制 `store=false` 和 `stream=true`；compact 路径移除 `store` 和 `stream`。这是内部端点兼容逻辑，不应反向污染公共 API Key 的 Responses 转换。
- OAuth Codex 请求会移除内部端点不接受的公共 OpenAI 字段，例如 `max_output_tokens`、`max_completion_tokens`、`temperature`、`top_p`、`frequency_penalty`、`presence_penalty`、`service_tier`、`metadata`、`user`、`safety_identifier`、`prompt_cache_key`、`prompt_cache_retention`、`top_logprobs`、`stream_options`、`include`、`previous_response_id`、`truncation`、`prompt`、`background`、`conversation`、`context_management`、`parallel_tool_calls` 和 `max_tool_calls`。这些字段在 API Key 公共端点可保留，但不能假定 ChatGPT Codex 内部端点接受。

因此，路由拆分也是协议转换正确性的一部分。OAuth Responses 请求不能被当作公共 `/v1/responses` 请求处理。

## 官方 Schema 字段状态矩阵

状态标记：

- `保真转换`：目标协议有等价结构，字段语义可保持。
- `有损转换`：目标协议能表达主要意图，但会丢失子字段、类型或生命周期信息。
- `只透传`：仅在公共 Responses/API Key 直连或同协议场景保留，不降级到其他协议。
- `不支持`：目标协议没有稳定等价结构，转换器不得伪造。
- `内部端点剥离`：公共协议字段，但 OpenAI OAuth Codex 内部端点不接受，发往 Codex 前移除。
- `内部扩展`：LandGate 为跨协议保真使用的内部字段，不属于公共上游协议。

### OpenAI Responses 请求字段

| 官方字段族 | 公共 Responses(API Key) | 到 Chat Completions | 到 Anthropic Messages | OAuth Codex |
| --- | --- | --- | --- | --- |
| `model` | 保真转换 | 保真转换 | 保真转换 | 保留 |
| `input` message：`system`/`developer`/`user`/`assistant` | 保真转换 | 有损转换：`instructions`/developer/input item 重排为 Chat messages | 有损转换：合并 system/developer，强制角色交替 | 保留，system/developer 会抽到 `instructions` |
| `instructions` | 保真转换 | 保真转换为首条 `system` | 保真转换到顶层 `system` | 保留，缺省时补默认指令 |
| `input_text` | 保真转换 | 保真转换 | 保真转换 | 保留 |
| `input_image.image_url/detail` | 保真转换 | 保真转换到 `image_url` content part | 保真转换 URL/base64 子集 | 保留 |
| `input_audio` | 保真转换 | 保真转换到 Chat `input_audio` part | 不支持 | 保留但不保证内部端点能力 |
| `input_file.file_data/file_id/filename` | 保真转换 | 保真转换到 Chat `file` 子集 | 保真转换到 Anthropic `document` 子集 | 保留但不保证内部端点能力 |
| `input_file.file_url` | 保真转换 | 不支持：Chat `file` part 无同义字段 | 保真转换到 Anthropic URL document | 保留但不保证内部端点能力 |
| `tools`：`function` | 保真转换 | 保真转换 | 保真转换到 Anthropic tool | 保留 |
| `tools`：`custom` | 保真转换 | 保真转换到 Chat custom tool | 不支持 | 保留但不保证内部端点能力 |
| hosted tools：`web_search`/`web_search_preview`/`google_search` | 保真转换 | 有损转换为 `web_search_options` | 有损转换为 `web_search_20250305` | 保留但不保证内部端点能力 |
| hosted tools：`file_search`/`computer_use_preview`/`mcp`/`image_generation`/`code_interpreter`/`local_shell` | 只透传 | 不支持 | 不支持 | 保留但不保证内部端点能力 |
| `tool_choice`：function/custom/allowed_tools | 保真转换 | 保真转换到 Chat 对应嵌套形状 | function/web search 子集保真，其余不支持 | 保留 |
| `parallel_tool_calls` | 保真转换 | 保真转换 | `false` 转为 Anthropic `tool_choice.disable_parallel_tool_use=true`；无显式 `tool_choice` 时补 `{"type":"auto"}` 承载该字段 | 内部端点剥离 |
| `max_output_tokens` | 保真转换 | 保真转换到 `max_completion_tokens` | 保真转换到 `max_tokens` | 内部端点剥离 |
| `temperature` / `top_p` | 保真转换 | 保真转换 | 保真转换 | 内部端点剥离 |
| `text.format` | 保真转换 | 保真转换到 `response_format` | 不支持 | 保留但不保证内部端点能力 |
| `text.verbosity` | 保真转换 | 保真转换到 Chat `verbosity` | 不支持 | 保留但不保证内部端点能力 |
| `reasoning.effort` | 保真转换 | 保真转换到 `reasoning_effort` | 有损转换为 Anthropic `thinking`/`output_config` | 保留但不保证内部端点能力 |
| `include` | 只透传 | 仅 `message.output_text.logprobs` 可转为 Chat `logprobs=true` | 不支持 | 内部端点剥离 |
| `previous_response_id` / `truncation` / `prompt` / `background` / `conversation` / `context_management` | 只透传 | 不支持 | 不支持 | 内部端点剥离 |
| `metadata` / `user` / `safety_identifier` / `prompt_cache_key` / `prompt_cache_retention` | 保真转换 | Chat 支持子集保真 | Anthropic 仅 `metadata` 子集 | 内部端点剥离 |
| `store` | 保真转换，未传时默认 `false` | Chat 支持则保真 | 不支持 | 普通 Codex 强制 `false`；compact 移除 |
| `stream` | 保真转换 | 保真转换 | 保真转换 | 普通 Codex 强制 `true`；compact 移除 |
| `_landgate_stop_sequences` | 内部扩展 | 保真转换到 Chat `stop` | 保真转换到 Anthropic `stop_sequences` | 发往公共 Responses 前移除 |

### OpenAI Responses 响应与输出字段

| 官方字段族 | 到 Chat Completions | 到 Anthropic Messages | Responses 直连 |
| --- | --- | --- | --- |
| `id` / `model` / `created_at` / `status` | `created_at` 保真到 Chat `created`，`status` 有损到 `finish_reason` | 有损转换到 Anthropic envelope 和 `stop_reason` | 保真转换 |
| `output[].message.content[].output_text` | 保真转换到 assistant `content` | 保真转换到 Anthropic `text` block | 保真转换 |
| `output[].message.content[].refusal` | 有损转换为 assistant 文本 | 有损转换为 Anthropic text block | 保真转换 |
| `output[].reasoning.content[].reasoning_text` | 有损转换到兼容字段 `reasoning_content` | 保真转换到 `thinking` | 保真转换 |
| `output[].reasoning.summary[].summary_text` | 有损兜底到 `reasoning_content` | 有损兜底到 `thinking` | 保真转换 |
| `output[].reasoning.encrypted_content` | 不支持：不得泄漏到 Chat 文本 | 保真转换为 `thinking.signature` 或 `redacted_thinking.data` | 保真转换 |
| `output[].function_call` | 保真转换到 Chat `tool_calls[].function` | 保真转换到 Anthropic `tool_use` | 保真转换 |
| `output[].custom_tool_call` | 保真转换到 Chat custom `tool_calls` | 不支持 | 保真转换 |
| `output[].web_search_call` | 不支持 | 非流式不支持；流式有兼容 `server_tool_use`/`web_search_tool_result` | 保真转换 |
| hosted tool call 输出：`file_search_call`/`computer_call`/`code_interpreter_call`/`mcp_call`/`image_generation_call`/`local_shell_call` | 不支持，不伪造文本或 tool_calls | 不支持，不伪造 text/tool_use | 保真转换 |
| `usage.input_tokens/output_tokens/input_tokens_details.cached_tokens/output_tokens_details.reasoning_tokens` | 保真转换到 Chat usage、cached details 和 reasoning token details | 有损转换：Anthropic `input_tokens` 需扣除 cached，`cache_read_input_tokens` 保留，reasoning token 明细无等价字段 | 保真转换 |
| `error` / `incomplete_details` | 有损转换到 finish/error 语义 | 有损转换到 stop/error 语义 | 保真转换 |

### OpenAI Responses 流式事件

| 官方事件族 | 到 Chat stream | 到 Anthropic stream | Responses 直连 |
| --- | --- | --- | --- |
| `response.created` | 发送 assistant role chunk，`created_at` 映射到 Chat `created` | 发送 `message_start` | 保真透传 |
| `response.output_item.added/done`：`message` | 由 text/refusal delta 或 done 兜底输出 | 由 text/refusal delta 或 done 兜底输出 | 保真透传 |
| `response.output_text.delta/done` | 保真转换到 `delta.content` | 保真转换到 `text_delta` | 保真透传 |
| `response.refusal.delta/done` | 有损转换到 `delta.content` | 有损转换到 `text_delta` | 保真透传 |
| `response.output_item.added/done`：`function_call` | 保真转换到 `tool_calls` delta | 保真转换到 `tool_use` block | 保真透传 |
| `response.function_call_arguments.delta/done` | 保真转换并按 `output_index` 归属 | 保真转换到 `input_json_delta`，Read 工具有清洗兼容 | 保真透传 |
| `response.reasoning_summary_text.delta` / `response.reasoning_text.delta` | 有损转换到 `delta.reasoning_content` | 保真转换到 `thinking_delta` | 保真透传 |
| `response.output_item.added/done`：hosted tool calls | 不支持，不产生 Chat 文本/tool_calls | 不支持，不产生 Anthropic text/tool_use；`web_search_call` 例外 | 保真透传 |
| `response.completed` / `response.done` / `response.incomplete` / `response.failed` | 终止事件，输出 final chunk 和 `[DONE]` | 终止事件，输出 `message_delta`/`message_stop` | 保真透传 |
| terminal event `usage` 位于顶层或 `response.usage` | 保真解析 cached/reasoning token 明细 | 保真解析并转换 cached token 语义，reasoning token 明细无等价字段 | 保真透传 |

### OpenAI Chat Completions 字段

| 官方字段族 | 到 Responses IR | 到 Anthropic Messages |
| --- | --- | --- |
| `model` / `stream` / `temperature` / `top_p` / `service_tier` / `metadata` / `user` | 保真转换 | 经 Responses 中转后保真或 Anthropic 子集保真 |
| `messages[].role=system/developer/user/assistant/tool/function` | 保真转换到 Responses input items | 有损转换：system/developer 合并，角色交替约束 |
| user content `text` / `image_url.detail` / `input_audio` / `file` | 保真转换到 Responses content parts | Anthropic 支持 text/image/document 子集；audio 不支持 |
| assistant `content` / `refusal` | 保真转换到 Responses output_text/refusal | 保真或有损转换到 text |
| assistant `tool_calls` / legacy `function_call` | 保真转换到 Responses function/custom tool call | function 子集保真到 Anthropic `tool_use` |
| `tools` function/custom | 保真转换 | function 子集保真；custom 不支持 |
| `tool_choice` function/custom/allowed_tools | 保真转换到 Responses 扁平形状 | function 子集保真 |
| `web_search_options` | 有损转换到 Responses web search hosted tool | 有损转换到 Anthropic web search tool |
| `response_format` | 保真转换到 `text.format` | 不支持 |
| `reasoning_effort` | 保真转换到 `reasoning.effort` | 有损转换到 thinking |
| `verbosity` | 保真转换到 `text.verbosity` | 不支持 |
| `logprobs` / `top_logprobs` | 有损转换为 Responses include/request intent | 不支持 |
| `store` | 保真转换到 Responses；未传默认 `false` | 不支持 |
| `stop` | 内部扩展 `_landgate_stop_sequences` | 保真转换到 Anthropic `stop_sequences` |
| `n` / `seed` / `modalities` / `audio` / `prediction` / `presence_penalty` / `frequency_penalty` / `logit_bias` | 不支持：无稳定 Responses 同义字段时不伪造 | 不支持 |
| 兼容扩展 `assistant.reasoning_content` | 内部转换为 Responses reasoning item | 转为 Anthropic thinking |
| 非官方 assistant content part `thinking`/`reasoning` | 内部转换为 Responses reasoning item | 转为 Anthropic thinking |

### Chat Completions 响应与流式字段

| 官方字段族 | 到 Responses IR | 到 Anthropic Messages |
| --- | --- | --- |
| response envelope：`id`/`model`/`created`/`choices` | `created` 保真到 Responses `created_at`，`choices` 有损转换到 output | 经 Responses 中转有损转换 |
| `message.content` | 保真转换到 `output_text` | 保真转换到 Anthropic text |
| `message.refusal` | 保真转换到 Responses refusal | 有损转换到 Anthropic text |
| `message.tool_calls` / legacy `function_call` | 保真转换到 function/custom call output item | function 子集保真到 `tool_use` |
| `message.reasoning_content` 兼容扩展 | 内部转换到 reasoning output item | 转为 Anthropic thinking |
| `finish_reason=stop/length/content_filter/tool_calls` | `stop/tool_calls` → completed，`length` → incomplete `max_output_tokens`，`content_filter` → incomplete `content_filter` | 有损转换到 Anthropic stop_reason |
| `usage.prompt_tokens/completion_tokens/prompt_tokens_details.cached_tokens/completion_tokens_details.reasoning_tokens` | 保真转换到 Responses usage、cached details 和 reasoning token details | 有损转换为 Anthropic cache usage 语义；reasoning token 明细无等价字段 |
| stream `delta.content` / `delta.refusal` | 保真转换到 Responses text/refusal delta | 保真或有损转换到 Anthropic text_delta |
| stream `delta.tool_calls` | 保真转换，按 tool index 累积 | function 子集保真到 input_json_delta |
| stream `delta.reasoning_content` 兼容扩展 | 内部转换到 reasoning delta | 转为 Anthropic thinking_delta |
| stream terminal `finish_reason=length/content_filter` | 转为 Responses terminal incomplete，保留 `max_output_tokens/content_filter` reason | 有损转换到 Anthropic stop_reason |
| stream usage-only final chunk | 保真解析 cached/reasoning token 明细 | 保真解析 cached token 明细；reasoning token 明细无等价字段 |

Chat 响应中如果 assistant 只有 `tool_calls`/legacy `function_call` 且 `content` 为空或 `null`，转换到 Responses 时只生成对应 tool call output item，不额外伪造空 `message` output item。确实没有任何文本、拒绝、推理或工具输出时，使用空 `output` 表达无可转换内容。

Responses `status=incomplete` 反向转 Chat 时，`incomplete_details.reason=content_filter` 映射为 `finish_reason=content_filter`；`max_output_tokens` 或缺失/未知 reason 都映射为 `finish_reason=length`，避免把未完成误报为正常 `stop`。流式 `response.done`/`response.incomplete` 终止事件同样遵守该映射。

### Anthropic Messages 字段

| 官方字段族 | 到 Responses IR | 到 Chat Completions |
| --- | --- | --- |
| `model` / `stream` / `temperature` / `top_p` / `service_tier` / `metadata` | 保真转换 | Chat 支持子集保真 |
| `max_tokens` | 保真转换到 `max_output_tokens` | 保真转换到 `max_completion_tokens` |
| `stop_sequences` | 内部扩展 `_landgate_stop_sequences` | 保真转换到 Chat `stop` |
| `system` | 保真转换到 developer/system input | 有损转换到 Chat system/developer 消息 |
| message role `user`/`assistant` | 保真转换到 Responses input/output item | 有损转换到 Chat messages |
| content `text` | 保真转换 | 保真转换 |
| content `image` URL/base64 | 保真转换到 Responses image 子集 | 保真转换到 Chat image_url |
| content `document` URL/base64/file source | 保真转换到 Responses input_file 子集 | Chat file 子集保真；URL document 不支持 |
| content `document.citations/context/text/content source` | 不支持，不伪造 | 不支持 |
| `tool_use` / `tool_result` | 保真转换到 function_call/function_call_output | 保真转换到 Chat tool_calls/tool messages |
| `tools` / `tool_choice` | function 子集保真；`disable_parallel_tool_use` 映射 | function 子集保真 |
| `web_search_*` tools | 有损转换到 Responses web search tool | 有损转换到 Chat web_search_options |
| `thinking` / `output_config` | 保真转换到 Responses reasoning 配置 | 有损转换到 Chat reasoning_effort 或兼容字段 |
| assistant `thinking.signature` | 保真转换到 `encrypted_content` | 不支持，不暴露 |
| assistant `redacted_thinking.data` | 保真转换到 encrypted-only reasoning | 不支持，不暴露 |
| `top_k` | 不支持：OpenAI 无等价字段 | 不支持 |

### Anthropic Messages 响应与流式字段

| 官方字段族 | 到 Responses IR | 到 Chat Completions |
| --- | --- | --- |
| response envelope：`id`/`type`/`role`/`model`/`stop_reason` | 有损转换到 Responses envelope/status | 有损转换到 Chat envelope/finish_reason |
| content `text` | 保真转换到 output_text | 保真转换到 Chat content |
| content `tool_use` | 保真转换到 function_call | 保真转换到 Chat tool_calls |
| content `thinking` | 保真转换到 reasoning content/summary | 有损转换到 `reasoning_content` 兼容字段 |
| `thinking.signature` / `redacted_thinking.data` | 保真转换到 `encrypted_content` | 不支持，不暴露 |
| `stop_reason=max_tokens` | 转为 `status=incomplete` + `incomplete_details.reason=max_output_tokens` | 转为 Chat `finish_reason=length` |
| `stop_reason=model_context_window_exceeded` | 转为 `status=incomplete`，但不伪造 OpenAI reason | 有损转换到 Chat `finish_reason=length` |
| `stop_reason=end_turn/stop_sequence/tool_use/pause_turn/refusal` | 转为 `status=completed`；`tool_use` 由实际 tool block 表达 | 有损转换到 Chat `stop/tool_calls` 子集 |
| `usage.input_tokens/cache_creation_input_tokens/cache_read_input_tokens/output_tokens` | 保真转换为 Responses 总输入 token + cached details | 有损转换为 Chat usage cached details |
| stream `message_start` / `content_block_start` / `content_block_delta` / `message_delta` / `message_stop` | 保真转换到 Responses 事件生命周期 | 有损转换到 Chat chunks |
| stream `thinking_delta` / `signature_delta` | 保真转换到 reasoning delta + encrypted_content | thinking 有损到 `reasoning_content`；signature 不暴露 |
| stream `input_json_delta` | 保真转换到 function_call arguments delta | 保真转换到 Chat tool_calls delta |
| stream error event | 有损转换到 `response.failed` | 有损转换到 Chat/网关错误语义 |

### 认证与端点字段

| 场景 | 官方依据 | 当前状态 |
| --- | --- | --- |
| OpenAI API Key | OpenAI 公共 API authentication，`Authorization: Bearer <api_key>` | 保真使用公共 Chat/Responses 端点 |
| OpenAI OAuth Codex | Codex/ChatGPT 内部端点兼容，不等同公共 `/v1/responses` | 内部端点剥离公共字段，普通路径强制 `stream=true/store=false`，compact 移除二者 |
| Anthropic API Key | Anthropic Messages 官方 headers/body | Messages 协议转换保真 |
| Anthropic OAuth/Claude Code mimicry | Claude Code 兼容行为 | 只作用于 Anthropic OAuth 请求规范化，不进入 OpenAI/Gemini 转换 |

## 请求转换矩阵

### Chat Completions 到 Responses IR

精确映射：

- `model`
- `stream`
- `temperature`
- `top_p`
- `service_tier`
- `metadata`
- `user`
- `safety_identifier`
- `prompt_cache_key`
- `prompt_cache_retention`
- `parallel_tool_calls`
- `top_logprobs`
- `max_completion_tokens` 或 `max_tokens` 映射到 `max_output_tokens`，不做截断
- `reasoning_effort` 映射到 `reasoning.effort`
- `response_format` 映射到 `text.format`；`json_schema` 必须有非空 `json_schema.name` 和 `schema`，缺失则不构造不完整的 Responses `text.format`
- `verbosity` 映射到 `text.verbosity`
- `logprobs=true` 映射为 Responses `include=["message.output_text.logprobs"]`
- 显式 `store` 保留到 Responses；未传 `store` 时默认写入 `store=false`
- `stop` 映射到内部 `_landgate_stop_sequences`

`metadata` 只保留对象形态；字符串、数组等非对象 metadata 不透传，避免向 Responses 发送非法 metadata envelope。
`instructions`、`service_tier`、`user`、`safety_identifier`、`prompt_cache_key`、`prompt_cache_retention` 只保留非空字符串形态；对象/数组等非文本形态不透传。

消息映射：

- `system` 和 `developer` 消息转换为相同角色的 input message；数组 content 只抽取非空文本 part，数组里没有有效文本或 content 是不支持对象时，不生成空 Responses instruction/message item。
- `user` 的文本、图片、音频和文件分片分别转换为 `input_text`、`input_image`、`input_audio` 和 `input_file`。
- Chat `image_url.detail` 会保留到 Responses `input_image.detail`；空 URL 和空载荷 base64 data URL 会被跳过。
- Chat 数组 content 中的空 text part 不生成 Responses `input_text`；`input_audio` 只有同时包含非空 `data` 和 `format` 时才转换为 Responses `input_audio`。数组 content 只包含无效、空或不支持 part 时，不生成空 Responses message item。
- `assistant` 文本转换为 assistant `message` item。
- Chat 兼容字段 `assistant.reasoning_content` 转换为独立 Responses `reasoning` input item，写入 `content[].reasoning_text.text` 和 summary；不会混入 assistant `output_text`。
- Chat 官方 assistant content part 不定义 `thinking`/`reasoning` 类型；若兼容客户端传入这类扩展 part，转换器按 Responses reasoning input item 保留明文推理，不再包进 `<thinking>` 文本标签，也不会污染 assistant `output_text`。
- `assistant` content part 中的 `refusal` 会以文本兼容形式保留。
- `assistant.tool_calls[]` 转换为一个或多个 `function_call` input item。
- 旧版 `assistant.function_call` 也转换为 `function_call` input item。
- `tool` 消息转换为 `function_call_output` input item。
- 旧版 `function` 消息使用 `name` 作为 call id，转换为 `function_call_output` input item。
- Chat tool/function 消息内容为空或缺少可拍平文本时，Responses `function_call_output.output` 使用空字符串，不写入人工占位文本。
- Chat `tool_calls[]` 进入 Responses IR 时必须有非空 `id` 和 function/custom `name`；Chat `tool` 消息必须有非空 `tool_call_id`；旧版 `function` 消息和旧版 `assistant.function_call` 必须有非空 `name`。缺失时不构造空 `call_id/name` 的 Responses tool item。旧版 `assistant.function_call` 没有独立 id，只有在 `name` 有效时才沿用 `name` 作为 `call_id`。
- Chat function tool 和旧版 `functions[]` 的 `parameters` 只有对象 schema 会保留；客户端传入非对象 `parameters` 时归一化为空 object schema，不把非法 schema 类型写入 Responses tool。

工具映射：

- Chat function tool 转换为 Responses function tool。
- 保留 `function.strict`。
- Chat 嵌套形式的 `tool_choice: {"type":"function","function":{"name":"..."}}` 转换为 Responses 扁平形式的 `{"type":"function","name":"..."}`。
- Chat custom tool 转换为 Responses custom tool，保留 `name`、`description` 和 `format`。
- Chat 嵌套形式的 `tool_choice: {"type":"custom","custom":{"name":"..."}}` 转换为 Responses 扁平形式的 `{"type":"custom","name":"..."}`。
- Chat 文本 `tool_choice` 只保留 `auto`、`none` 和 `required`；旧式 `function_call` 文本只保留 `auto` 和 `none`，`required` 不套用到 legacy 字段。
- Chat `allowed_tools` 形式的 `tool_choice` 会转换为 Responses 扁平 `allowed_tools`，其中 function/custom 工具名从 `function.name` / `custom.name` 提升到顶层 `name`，并保留 `mode`。
- Chat assistant `tool_calls[]` 中的 custom call 转换为 Responses `custom_tool_call` input/output item，保留 `call_id`、`name` 和 `input`。
- Chat `web_search_options` 转换为 Responses `web_search_preview` tool；`search_context_size` 和 approximate `user_location` 会保留。

### Responses IR 到 Chat Completions

在 Chat 支持的字段上精确映射：

- `model`
- `service_tier`
- `temperature`
- `top_p`
- `metadata`
- `user`
- `store`
- `safety_identifier`
- `prompt_cache_key`
- `prompt_cache_retention`
- `parallel_tool_calls`
- `top_logprobs`，同时打开 Chat `logprobs=true`
- `max_output_tokens` 映射到 `max_completion_tokens`
- 内部 `_landgate_stop_sequences` 映射到 `stop`
- `reasoning.effort` 映射到 `reasoning_effort`
- `text.format` 映射到 `response_format`；`json_schema` 降级到 Chat 时必须有非空 `name` 和 `schema`，缺失则不构造非法 `response_format`
- `text.verbosity` 映射到 Chat `verbosity`
- `include` 中存在 `message.output_text.logprobs` 时映射为 Chat `logprobs=true`

`metadata` 只保留对象形态；非对象 metadata 不降级到 Chat。
`service_tier`、`user`、`safety_identifier`、`prompt_cache_key`、`prompt_cache_retention` 只保留非空字符串形态；对象/数组等非文本形态不降级到 Chat。

输入映射：

- `instructions` 转换为开头的 `system` 消息；空白 `instructions` 会被忽略，不生成空白 system 消息。
- Responses 字符串 `input` 转换为单条 Chat `user` 消息。
- `developer` 保持为 `developer`；不会降级为 `system`。
- 连续的 `function_call` input item 合并为一条带有多个 `tool_calls` 的 assistant 消息。
- `function_call_output` 转换为 `tool` 消息。
- Responses `function_call_output.output` 缺失时，Chat tool message `content` 使用空字符串，不写入人工占位文本。
- input message content 中的 `refusal` 降级为 Chat 文本兼容内容，不构造非官方 content part。
- Responses input message 数组 content 中的空 `input_text`/`output_text`/`text`/`refusal` part 会被跳过；content 不是字符串或数组、或数组只包含空文本/无效 part 时，不生成 Chat 空消息。
- `input_image` 转换为 Chat 的 `image_url` content part，并保留 `detail`。
- `input_audio` 只有同时包含非空 `data` 和 `format` 时才转换为 Chat 的 `input_audio` content part。
- `input_file` 转换为 Chat 的 `file` content part，保留 `file_data`、`file_id` 和 `filename`。
- Responses `input_file.file_url` 没有 Chat `file` content part 的同义字段，降级到 Chat 时不会伪造成 `file_data`。
- Responses message content 如果只包含 Chat 不支持或无效的 part（例如 `input_file.file_url`、缺 payload 的 `input_audio`、空 `input_image`），该 message 会被丢弃，不伪造成空字符串消息。
- Responses `web_search_preview` / `web_search` tool 转换为 Chat `web_search_options`；其他非 function tool 不塞进 Chat `tools`。
- Responses custom tool 转换为 Chat custom tool，保留 `name`、`description` 和 `format`。
- Responses 输入或输出里的 `function_call`/`custom_tool_call` 降级到 Chat assistant `tool_calls` 时，必须同时有非空 `call_id` 和 `name`；缺失时不构造空 id/name 的非法 Chat tool call。`function_call_output` 缺少 `call_id` 时也不构造无归属 tool message。
- Responses function tool 的 `parameters` 只有对象 schema 会保留到 Chat `function.parameters`；非对象 `parameters` 归一化为空 object schema。
- Responses 扁平 custom `tool_choice` 转换为 Chat 嵌套形式的 `{"type":"custom","custom":{"name":"..."}}`。
- Responses 扁平 `allowed_tools` 形式的 `tool_choice` 转换为 Chat 嵌套 `allowed_tools`，function/custom 工具名分别进入 `function.name` / `custom.name`，并保留 `mode`。

### Responses 到 Responses IR

Responses 本身就是内部 IR，所以公共 API Key 直连 Responses 时应尽量透传官方字段：

- `previous_response_id`
- `truncation`
- `prompt`
- `background`
- `conversation`
- `instructions`
- `max_output_tokens`
- `context_management`（官方数组形状，例如 `[{"type":"compaction","compact_threshold":2000}]`）
- `include`
- `tools`
- `tool_choice`
- `reasoning`
- `text`

只有 LandGate 内部扩展字段（例如 `_landgate_stop_sequences`）会在发往公共 Responses 上游前移除。

公共 Responses 直连时，`include` 的官方请求项应原样透传；当前文档和测试覆盖：

- `code_interpreter_call.outputs`
- `computer_call_output.output.image_url`
- `file_search_call.results`
- `message.input_image.image_url`
- `message.output_text.logprobs`
- `reasoning.encrypted_content`
- `web_search_call.results`
- `web_search_call.action.sources`

公共 Responses 直连时，Responses hosted tools 也应原样透传；当前文档和测试覆盖：

- `file_search`
- `computer_use_preview`
- `mcp`
- `image_generation`
- `code_interpreter`
- `local_shell`

输出映射：

- message 的 `output_text` 转换为 assistant `content`。
- message 的 `refusal` 以兼容的文本内容形式保留。
- `function_call` output item 转换为 assistant `tool_calls`。
- `custom_tool_call` output item 转换为 assistant custom `tool_calls`。
- Chat 响应 `tool_calls[]` 进入 Responses IR 时同样必须有非空 `id` 和 function/custom `name`；旧版响应 `function_call` 必须有非空 `name`，否则不构造 Responses `function_call`。
- reasoning item 优先使用 `content[].reasoning_text.text` 转换为 `reasoning_content`；没有 reasoning content 时使用 `summary[].summary_text.text` 兜底。
- `encrypted_content` 没有 Chat Completions 等价字段，不能泄漏到 `reasoning_content`。

### Anthropic Messages 到 Responses IR

精确映射：

- `model`
- `stream`
- `service_tier`
- `temperature`
- `top_p`
- `metadata`
- `max_tokens` 映射到 `max_output_tokens`，不做截断
- `stop_sequences` 映射到内部 `_landgate_stop_sequences`
- `tool_choice.disable_parallel_tool_use=true` 映射到 `parallel_tool_calls=false`

`metadata` 只保留对象形态；非对象 metadata 不写入 Responses IR。
`service_tier` 只保留非空字符串形态；对象/数组等非文本形态不写入 Responses IR。

消息映射：

- 顶层 `system` 转换为 `developer` input message。
- `user` 的 text、image、document、tool_result 分片转换为 user input item 或 `function_call_output`。
- `tool_result` 中的文本进入 `function_call_output.output`；图片和可保真 document 子集延迟为单独 user content，分别转换为 Responses `input_image` 和 `input_file`，避免把二进制/文件内容伪造成工具输出字符串。没有文本时 `output` 使用空字符串，不写入人工占位文本。
- Anthropic `tool_result` 进入 Responses IR 时必须有非空 `tool_use_id`；缺失时不生成 `function_call_output`。
- Anthropic 普通 tool 的 `input_schema` 只有对象 schema 会保留；缺失、`null` 或非对象 schema 统一归一化为 `{"type":"object","properties":{}}`，避免把非法 schema 类型写入 OpenAI tool `parameters`。
- Anthropic base64 image source 转换为 Responses data URL；Anthropic URL image source 转换为 Responses `image_url`。
- Anthropic `document` content block 的可保真子集转换为 Responses `input_file`：URL source 转 `file_url`，base64 source 转 data URI `file_data`，file source 转 `file_id`，`title` 转 `filename`。
- Anthropic image/document source 必须有非空 URL、base64 data 或 file_id；数组 content 只包含无效/不支持 part 时，不生成空 Responses message item。
- `assistant` 文本转换为 assistant message content。
- `assistant` 的 tool_use block 转换为 `function_call` item。
- Anthropic `tool_use` 进入 Responses IR 时必须有非空 `id` 和 `name`；缺失时不生成 `function_call`，也不随机补 `call_id`。
- 多轮请求中的 `assistant` thinking block 转换为 Responses reasoning input item；`thinking` 文本写入 `content[].reasoning_text.text` 和 summary，`signature` 写入 `encrypted_content`。
- 多轮请求中的 `assistant` redacted_thinking block 转换为只有 `encrypted_content` 的 Responses reasoning input item。
- Anthropic `web_search_*` tool 转换为 Responses web search tool，并保留共同支持的对象形态 `user_location`；非对象 `user_location` 不透传。
- 响应中的 `thinking` block 转换为 reasoning output item。
- 响应中的 `thinking.signature` 保留为 reasoning item 的 `encrypted_content`。
- 响应中的 `redacted_thinking.data` 保留为 reasoning item 的 `encrypted_content`，不伪造成明文 thinking。
- Anthropic 响应 content block 的相对顺序必须保留；连续 text block 可合并为一个 Responses message item，但遇到 thinking/redacted_thinking/tool_use 前必须先 flush 已收集文本，不能把所有文本统一延后到 output 末尾。
- Anthropic 响应 `content` 为空、只包含空 text block，或只包含不支持的专用 block 时，Responses `output` 保持为空，不伪造空 `output_text`。
- Anthropic stream 的 `tool_use` content block 缺 `id` 或 `name` 时不打开 Responses `function_call` output item，后续 `input_json_delta` 不会被输出为孤立 arguments delta。

推理映射：

- `thinking` 或 `output_config` 表示启用 Responses `reasoning`。
- 普通 Anthropic Messages 请求不会被隐式转换为 reasoning 请求。
- Anthropic extended thinking 的 `signature` 和 `redacted_thinking.data` 都是不透明数据；转换器只搬运，不解析、不展示。

用量映射：

- Anthropic 的 `cache_read_input_tokens` 表示为 Responses 的 `input_tokens_details.cached_tokens`。
- Anthropic 官方说明总输入 token 是 `input_tokens + cache_creation_input_tokens + cache_read_input_tokens`。转换到 Responses 时，`input_tokens` 使用这个总和；`input_tokens_details.cached_tokens` 只表示 `cache_read_input_tokens`。
- Responses 的 `input_tokens` 包含 cached tokens；Anthropic 的 `input_tokens` 不包含 cached/read tokens，也不包含 cache creation tokens。

### Responses IR 到 Anthropic Messages

在 Anthropic 支持的字段上精确映射：

- `model`
- `stream`
- `service_tier`
- `temperature`
- `top_p`
- `metadata`
- `max_output_tokens` 映射到 `max_tokens`
- 内部 `_landgate_stop_sequences` 映射到 `stop_sequences`
- `parallel_tool_calls=false` 映射到 Anthropic `tool_choice.disable_parallel_tool_use=true`；如果 IR 没有显式 `tool_choice`，或显式 `tool_choice` 不是 Anthropic 可表达的 function/web search 子集，则生成 `{"type":"auto","disable_parallel_tool_use":true}`，因为 Anthropic 的禁用并行工具开关只能挂在 `tool_choice` 对象上。

`metadata` 只保留对象形态；非对象 metadata 不写入 Anthropic 请求。
`service_tier` 只保留非空字符串形态；对象/数组等非文本形态不写入 Anthropic 请求。

输入映射：

- `instructions`、`system` 和 `developer` input 合并到 Anthropic 顶层 `system`；空白 `instructions`、空白文本和只含空白文本 part 的 instruction input 会被忽略，不生成空白 `system`。
- `user` input item 转换为 Anthropic user message。
- Responses data URL 图片转换为 Anthropic base64 image source；HTTP(S) 图片 URL 转换为 Anthropic URL image source。
- Responses `input_file` 的可保真子集转换为 Anthropic `document`：`file_url` 转 URL source，data URI `file_data` 转 base64 source，`file_id` 转 file source，`filename` 转 `title`。
- Responses message content 如果只包含 Anthropic 不支持的 part（例如 `input_audio` 或 custom tool call content part）或空 `input_text`/`text` part，该 message 会被丢弃，不伪造成空字符串或空 `text` block。
- Responses reasoning input item 转换为 assistant thinking 或 redacted_thinking block。存在 `content`/`summary` 时输出 thinking，并在有 `encrypted_content` 时作为 `signature`；只有 `encrypted_content` 时输出 redacted_thinking。
- `function_call` 转换为 assistant `tool_use`。
- `function_call_output` 转换为 user `tool_result`。
- Responses `function_call_output.output` 缺失时，Anthropic `tool_result` 使用空文本内容，不写入人工占位文本。
- 降级到 Anthropic `tool_use` 时必须有非空 `call_id` 和 `name`；降级到 Anthropic `tool_result` 时必须有非空 `call_id`。缺失时不构造空 `id/name/tool_use_id`。
- Responses function tool 的 `parameters` 只有对象 schema 会保留为 Anthropic `input_schema`；缺失、`null` 或非对象 schema 统一归一化为空 object schema。
- Responses `web_search` / `web_search_preview` / `google_search` tool 转换为 Anthropic `web_search_20250305`，并保留共同支持的对象形态 `user_location`；非对象 `user_location` 不透传。
- Responses web search hosted tool 的 `tool_choice` 转换为 Anthropic `{"type":"tool","name":"web_search"}`。
- 连续的同角色消息会被合并，以满足 Anthropic 的角色交替约束。

输出映射：

- `output_text` 和 `refusal` 都转换为 Anthropic text block。
- `function_call` 转换为 `tool_use`。
- Responses 输出 `function_call` 缺少非空 `call_id` 或 `name` 时不构造 Anthropic `tool_use`，也不把 `stop_reason` 判为 `tool_use`。
- reasoning item 优先使用 `content[].reasoning_text.text` 转换为 Anthropic `thinking` block；没有 reasoning content 时使用 `summary[].summary_text.text` 兜底。
- reasoning item 同时有明文 reasoning 和 `encrypted_content` 时，`encrypted_content` 转为 Anthropic `thinking.signature`。
- reasoning item 只有 `encrypted_content` 时，转为 Anthropic `redacted_thinking.data`。
- Responses `output` 为空或只包含 Anthropic 不支持的 hosted tool call 时，Anthropic `content` 保持为空，不伪造空 `text` block。

## 流式转换

流式转换基于事件，而不是逐行复制。

Chat stream 到 Responses stream：

- role chunk 创建 `response.created`，Chat chunk 的 `created` 映射为 Responses `created_at`。
- content chunk 生成 `response.output_text.delta`。
- refusal chunk 生成 `response.refusal.delta`。
- reasoning chunk 生成独立的 reasoning item。
- tool call chunk 按 Chat tool index 追踪，使交错的调用可以分别累积。
- Chat stream `tool_calls[]` 必须先提供非空 `id` 和 function `name` 才会创建 Responses `function_call` item；只有 arguments delta 而没有已建立的有效 tool state 时，不输出孤立的 `response.function_call_arguments.delta`。旧式 stream `delta.function_call` 没有独立 id，只有在先看到非空 `name` 后才使用该 `name` 作为 `call_id` 并接收后续 arguments。
- 最终 `[DONE]` 关闭打开的 item；`finish_reason=length/content_filter` 发出 `response.incomplete`，其他可完成状态发出 `response.completed`。
- finish reason 之后、`[DONE]` 之前的 usage-only chunk 可以被接受。
- usage-only chunk 中的 `completion_tokens_details.reasoning_tokens` 映射到 Responses `output_tokens_details.reasoning_tokens`；Chat 特有的 prediction/audio token 明细没有 Responses 稳定同义字段时不伪造。

Responses stream 到 Chat stream：

- `response.created.created_at` 映射为 Chat chunk 的 `created`。
- `response.output_text.delta` 转换为 Chat `delta.content`。
- 如果没有看到 delta，最终的 `output_text.done`、`content_part.done` 或 `response.completed.output` 可以提供兜底文本。
- function call item 转换为 Chat function `tool_calls` delta；custom tool call item 在 `response.output_item.added/done` 或 `response.completed.output` 兜底中转换为 Chat custom `tool_calls` delta。
- function/custom tool call item 在 `response.output_item.added/done` 中缺少非空 `call_id` 或 `name` 时，不建立 `output_index` 映射，也不输出 Chat `tool_calls` delta。
- `response.reasoning_summary_text.delta` 和 `response.reasoning_text.delta` 都转换为 Chat `delta.reasoning_content`。
- cached token 明细转换为最终 usage 中的 `prompt_tokens_details.cached_tokens`。
- reasoning token 明细转换为最终 usage 中的 `completion_tokens_details.reasoning_tokens`。
- `response.completed`、`response.done`、`response.incomplete` 和 `response.failed` 都视为 Responses 终止事件。
- 终止事件中的 usage 可以位于 `response.usage` 或顶层 `usage`；两种位置都要解析。
- custom tool call 的最终 item 可以流式降级为 Chat custom `tool_calls`；但官方 Responses 流式 custom tool 增量事件没有像 `response.function_call_arguments.delta` 一样在本项目中稳定落地前，不伪造增量事件。
- `file_search_call`、`computer_call`、`code_interpreter_call`、`mcp_call`、`image_generation_call`、`local_shell_call` 等 hosted tool call 的 `response.output_item.added/done` 和 completed output 不会被降级为 Chat `delta.content` 或 `delta.tool_calls`。

Anthropic stream 到 Responses stream：

- `message_start` 创建 Responses response。
- `content_block_start` 打开 message、reasoning 或 function_call output item。
- `text_delta`、`thinking_delta` 和 `input_json_delta` 映射到对应的 Responses delta。
- `signature_delta` 累积到 reasoning item 的 `encrypted_content`。
- `message_delta.stop_reason=max_tokens` 映射为 `response.incomplete` + `max_output_tokens`；`model_context_window_exceeded` 映射为 `response.incomplete` 但不伪造 OpenAI reason；其他可完成 stop_reason 映射为 `response.completed`。
- 流式 usage 同非流式一样：`input_tokens + cache_creation_input_tokens + cache_read_input_tokens` 汇总到 Responses `input_tokens`，`cache_read_input_tokens` 映射到 `cached_tokens`。
- Anthropic stream error event 转换为 `response.failed`。

Responses stream 到 Anthropic stream：

- text/refusal delta 转换为 text content block delta。
- `response.reasoning_summary_text.delta` 和 `response.reasoning_text.delta` 都转换为 Anthropic `thinking_delta`。
- function call item 转换为 tool_use content block。
- function call item 在 `response.output_item.added/done` 中缺少非空 `call_id` 或 `name` 时，不建立 `output_index` 到 Anthropic content block 的映射；后续 arguments delta/done 若没有匹配到有效 tool block，也不输出孤立的 `input_json_delta`。
- 当上游流只发送 completed output 时，使用最终 output 作为兜底。
- completed output 兜底中的 reasoning item 和非流式一致：优先使用 `content[].reasoning_text.text`，没有时才使用 `summary[].summary_text.text`。
- Responses cached tokens 转换回 Anthropic `cache_read_input_tokens`。
- `response.completed`、`response.done`、`response.incomplete` 和 `response.failed` 都视为 Responses 终止事件。
- 终止事件中的 usage 可以位于 `response.usage` 或顶层 `usage`；两种位置都要解析。
- `file_search_call`、`computer_call`、`code_interpreter_call`、`mcp_call`、`image_generation_call`、`local_shell_call` 等 hosted tool call 的 `response.output_item.added/done` 和 completed output 不会被降级为 Anthropic `text_delta` 或 `tool_use`；`web_search_call` 是单独兼容路径，可输出 Anthropic `server_tool_use` 和 `web_search_tool_result`。

## 矩阵驱动测试状态

已经有明确测试覆盖的字段族：

- Chat ↔ Responses 基础 message、image、audio、file、function tool、custom tool、allowed_tools、web_search_options；Chat/Responses 文件 part 缺少非空 `file_data`/`file_id` 时不构造空 `input_file`/`file`，Responses `input_file.file_url` 降级到 Chat 时不伪造成 `file_data` 或空 file part，音频 part 缺少非空 `data`/`format` 时不构造空 `input_audio`，unsupported `tool_choice` / `allowed_tools` 项双向不透传，未知文本 `tool_choice` 模式不透传，缺名 tools/tool_choice 不构造空名结构。
- Chat `response_format` ↔ Responses `text.format`，覆盖 `json_schema`（含 `name`、`description`、`schema`、`strict`）、`json_object` 和 `text`。
- Chat `verbosity`、`reasoning_effort`、`logprobs/top_logprobs`、`prompt_cache_key`、`prompt_cache_retention`、`safety_identifier`。
- Chat 专有且无 Responses 等价物的 `n`、`seed`、`modalities`、`audio`、`prediction`、`presence_penalty`、`frequency_penalty`、`logit_bias` 不写入 IR。
- Chat 兼容扩展 `reasoning_content` 和非官方 assistant `thinking/reasoning` content part 拆分为 Responses reasoning item。
- Responses 公共直通保留 `previous_response_id`、`truncation`、`prompt`、`background`、`conversation`、`context_management`、`include`、hosted tools，并移除 LandGate 内部字段；hosted tools 子字段覆盖 file search `ranking_options/filters`、MCP `authorization/headers`、computer dimensions、code interpreter `container`。
- Responses hosted tool call 输出在 Chat/Anthropic 非流式和流式降级路径中不伪造文本或工具调用。
- Anthropic ↔ Responses text、image、document 安全子集、tool_use/tool_result、thinking/redacted_thinking、cache usage、web search tool 子集；Responses hosted tools、unsupported/custom `tool_choice`、Anthropic 未知对象或文本 `tool_choice` 和缺名普通 tool/tool_choice 不透传。
- Anthropic `top_k` 不透传到 Responses；`search_result`、`server_tool_use`、`web_search_tool_result` 非流式响应不伪造成 OpenAI 通用 message/tool。
- Anthropic `stop_reason=max_tokens` 精确转为 Responses incomplete/max_output_tokens；`model_context_window_exceeded` 只转为 incomplete，不把 Anthropic 专有原因写成 OpenAI 公共 reason。
- Responses/Chat/Anthropic 终止事件、usage、cached token、`response.failed` 和 Anthropic error event 的基础路径。
- OpenAI/Anthropic HTTP 错误 envelope：OpenAI 使用顶层 `error` 对象，Anthropic 使用顶层 `type:"error"` + `error.type/message`；Responses `response.failed` 降级到 Chat/Anthropic stream 时只输出目标协议终止事件，不把 error message 伪造成普通内容。
- Chat/Responses/Anthropic tool call 异常形状：进入 Responses IR 或降级到 Chat/Anthropic 时，缺必需 id/name 不构造 tool call/tool_use/function_call，缺 tool result id 不构造 tool output/tool_result/function_call_output；空 arguments 按方向分别保留为 `""` 或规范化为 `"{}"`，非 JSON arguments 作为官方字符串边界原样保留，tool result 顺序不重排。
- OpenAI API Key 公共 Responses 字段保留；覆盖状态字段（`previous_response_id`、`truncation`、`prompt`、`background`、`conversation`、`context_management`）、身份/缓存字段（`metadata`、`user`、`safety_identifier`、`prompt_cache_key`、`prompt_cache_retention`）、工具控制字段（`parallel_tool_calls`、`max_tool_calls`）和 `include/store/stream`。
- OpenAI OAuth Codex 内部端点字段剥离、`store/stream` 规范化和 system/developer 抽取；普通路径强制 `store=false/stream=true`，compact 路径移除 `store/stream`，且同样剥离公共 Responses 状态、身份、缓存和工具控制字段。

下一批应优先补的测试：

- Responses streaming custom tool call 官方事件若稳定，应补流式 custom tool call 的保真测试；在此之前继续保持“不伪造 custom 流式 delta”。

## 当前已知边界

以下是有意保留或当前接受的有损区域：

- Chat 专有且没有 Responses 等价物的参数不会被虚构进 IR。
- 转换到 Chat Completions 时，function、custom 和可降级为 `web_search_options` 的 web search tool 会保留；其他 Responses hosted tools 或专有 tool 类型不会塞进 Chat `tools`。
- Responses web search output 不会在 Anthropic 或 Chat 输出中表示。
- Chat `logprobs` 请求只能通过 Responses `include` 和 `top_logprobs` 表示请求意图；具体 token logprob 输出能否完整往返取决于上游响应形状。
- Chat `n`、`seed`、`modalities`、`audio`、`prediction`、`presence_penalty`、`frequency_penalty`、`logit_bias` 等没有稳定 Responses 等价字段的参数不会被强行写入 IR；若后续官方 Responses 暴露同义字段，应按字段语义补映射和测试。
- Responses `previous_response_id`、`truncation`、`prompt`、`background`、`conversation`、`context_management` 等状态型或 Responses 专有请求字段不会被降级到 Chat/Anthropic；只有直连 Responses 上游时应原样保留。
- Responses hosted tools 中，当前只有 function、custom 和 web search 能转换到 Chat/Anthropic；file search、computer use、MCP、image generation、code interpreter、local shell 等 Responses 专有 hosted tools 只在 Responses 直连场景透传。
- Responses 输出 item 中，只有 `message`、`reasoning`、`function_call` 和 `custom_tool_call` 具备可降级到 Chat 的结构；`file_search_call`、`computer_call`、`code_interpreter_call`、`mcp_call`、`image_generation_call`、`local_shell_call` 等 hosted tool call 输出不会被伪造成 Chat 文本或 `tool_calls`。
- Responses 输出 item 中，只有 `message`、`reasoning` 和 `function_call` 具备可降级到 Anthropic Messages 的结构；`file_search_call`、`computer_call`、`code_interpreter_call`、`mcp_call`、`image_generation_call`、`local_shell_call` 等 hosted tool call 输出不会被伪造成 Anthropic `text` 或 `tool_use`。
- Anthropic `document` 只转换 URL/base64/file_id 这类能对应 Responses `input_file` 的文件输入子集；`citations`、`context`、text document source、content document source 等 Anthropic 专有文档语义不会伪造成 OpenAI 字段。
- Anthropic `search_result`、`server_tool_use`、`web_search_tool_result` 等专用内容块没有完全等价的 OpenAI Chat/Responses 通用结构；除已实现的 web search 流式兼容输出外，当前不伪造跨协议结构。
- Responses reasoning 的 `encrypted_content` 只按 opaque 数据搬运；转换为 Chat 时不暴露为普通文本，转换为 Anthropic 时仅进入 `thinking.signature` 或 `redacted_thinking.data`。
- Chat 官方协议没有 assistant `thinking`/`reasoning` content part；LandGate 仅把这类兼容扩展作为 reasoning item 保留，不把它们降级成普通输出文本。
- Chat `file` content part 只有 `file_data` 或 `file_id` 是可转换为 Responses `input_file` 的有效载荷；只有 `filename`、空白 `file_id` 或空 `file_data` 的 part 会被跳过，避免构造无文件来源的 `input_file`。
- OpenAI OAuth Codex 端点兼容性要求移除内部端点不接受的公共 Responses 字段。
- Anthropic 专有的 `top_k` 没有 OpenAI Responses 等价物，不会透传到 OpenAI 上游请求。

未来变更应保留以下规则：

- 不要在转换器中根据模型名称推断模型能力。
- 不要在转换器中截断 token 限制。
- 除非客户端或路由明确要求，否则不要强制流式响应。
- 内部专用字段，例如 `_landgate_stop_sequences`，不得出现在直接发送给 Responses 上游的请求中。
