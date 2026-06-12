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

OpenAI OAuth 账号在本项目中不同于公共 API Key 账号：

- OAuth 账号路由到 ChatGPT Codex 内部 Responses 端点。
- 请求体会被规范化，以兼容 Codex 内部端点。
- 同样使用 `Authorization: Bearer <access_token>` 这种请求头形式，但 token 的签发方、刷新生命周期、端点以及可接受的请求字段都不同于 API Key。
- OAuth 的 401 可以通过刷新 token 恢复；API Key 的 401 被视为凭证失败。

因此，路由拆分也是协议转换正确性的一部分。OAuth Responses 请求不能被当作公共 `/v1/responses` 请求处理。

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
- `parallel_tool_calls`
- `max_completion_tokens` 或 `max_tokens` 映射到 `max_output_tokens`，不做截断
- `reasoning_effort` 映射到 `reasoning.effort`
- `response_format` 映射到 `text.format`
- `stop` 映射到内部 `_landgate_stop_sequences`

消息映射：

- `system` 和 `developer` 消息转换为相同角色的 input message。
- `user` 的文本和图片分片转换为 `input_text` 和 `input_image`。
- `assistant` 文本转换为 assistant `message` item。
- `assistant.tool_calls[]` 转换为一个或多个 `function_call` input item。
- 旧版 `assistant.function_call` 也转换为 `function_call` input item。
- `tool` 消息转换为 `function_call_output` input item。
- 旧版 `function` 消息使用 `name` 作为 call id，转换为 `function_call_output` input item。

工具映射：

- Chat function tool 转换为 Responses function tool。
- 保留 `function.strict`。
- Chat 嵌套形式的 `tool_choice: {"type":"function","function":{"name":"..."}}` 转换为 Responses 扁平形式的 `{"type":"function","name":"..."}`。

### Responses IR 到 Chat Completions

在 Chat 支持的字段上精确映射：

- `model`
- `temperature`
- `top_p`
- `metadata`
- `user`
- `parallel_tool_calls`
- `max_output_tokens` 映射到 `max_completion_tokens`
- 内部 `_landgate_stop_sequences` 映射到 `stop`
- `reasoning.effort` 映射到 `reasoning_effort`
- `text.format` 映射到 `response_format`

输入映射：

- `instructions` 转换为开头的 `system` 消息。
- `developer` 保持为 `developer`；不会降级为 `system`。
- 连续的 `function_call` input item 合并为一条带有多个 `tool_calls` 的 assistant 消息。
- `function_call_output` 转换为 `tool` 消息。
- 图片 input part 转换为 Chat 的 `image_url` content part。

输出映射：

- message 的 `output_text` 转换为 assistant `content`。
- message 的 `refusal` 以兼容的文本内容形式保留。
- `function_call` output item 转换为 assistant `tool_calls`。
- reasoning summaries 转换为 `reasoning_content`。

### Anthropic Messages 到 Responses IR

精确映射：

- `model`
- `stream`
- `temperature`
- `top_p`
- `metadata`
- `max_tokens` 映射到 `max_output_tokens`，不做截断
- `stop_sequences` 映射到内部 `_landgate_stop_sequences`
- `tool_choice.disable_parallel_tool_use=true` 映射到 `parallel_tool_calls=false`

消息映射：

- 顶层 `system` 转换为 `developer` input message。
- `user` 的 text、image、tool_result 分片转换为 user input item 或 `function_call_output`。
- `assistant` 文本转换为 assistant message content。
- `assistant` 的 tool_use block 转换为 `function_call` item。
- 响应中的 `thinking` block 转换为 reasoning output item。

推理映射：

- `thinking` 或 `output_config` 表示启用 Responses `reasoning`。
- 普通 Anthropic Messages 请求不会被隐式转换为 reasoning 请求。

用量映射：

- Anthropic 的 `cache_read_input_tokens` 表示为 Responses 的 `input_tokens_details.cached_tokens`。
- Responses 的 `input_tokens` 包含 cached tokens；Anthropic 的 `input_tokens` 不包含。

### Responses IR 到 Anthropic Messages

在 Anthropic 支持的字段上精确映射：

- `model`
- `stream`
- `temperature`
- `top_p`
- `metadata`
- `max_output_tokens` 映射到 `max_tokens`
- 内部 `_landgate_stop_sequences` 映射到 `stop_sequences`
- `parallel_tool_calls=false` 映射到 Anthropic `tool_choice.disable_parallel_tool_use=true`

输入映射：

- `instructions`、`system` 和 `developer` input 合并到 Anthropic 顶层 `system`。
- `user` input item 转换为 Anthropic user message。
- `function_call` 转换为 assistant `tool_use`。
- `function_call_output` 转换为 user `tool_result`。
- 连续的同角色消息会被合并，以满足 Anthropic 的角色交替约束。

输出映射：

- `output_text` 和 `refusal` 都转换为 Anthropic text block。
- `function_call` 转换为 `tool_use`。
- reasoning summaries 转换为 Anthropic `thinking` block。

## 流式转换

流式转换基于事件，而不是逐行复制。

Chat stream 到 Responses stream：

- role chunk 创建 `response.created`。
- content chunk 生成 `response.output_text.delta`。
- refusal chunk 生成 `response.refusal.delta`。
- reasoning chunk 生成独立的 reasoning item。
- tool call chunk 按 Chat tool index 追踪，使交错的调用可以分别累积。
- 最终 `[DONE]` 关闭打开的 item，并发出 `response.completed`。
- finish reason 之后、`[DONE]` 之前的 usage-only chunk 可以被接受。

Responses stream 到 Chat stream：

- `response.output_text.delta` 转换为 Chat `delta.content`。
- 如果没有看到 delta，最终的 `output_text.done`、`content_part.done` 或 `response.completed.output` 可以提供兜底文本。
- function call item 转换为 Chat `tool_calls` delta。
- cached token 明细转换为最终 usage 中的 `prompt_tokens_details.cached_tokens`。

Anthropic stream 到 Responses stream：

- `message_start` 创建 Responses response。
- `content_block_start` 打开 message、reasoning 或 function_call output item。
- `text_delta`、`thinking_delta` 和 `input_json_delta` 映射到对应的 Responses delta。
- `message_delta.stop_reason=max_tokens` 映射为 incomplete `max_output_tokens`。
- Anthropic stream error event 转换为 `response.failed`。

Responses stream 到 Anthropic stream：

- text/refusal delta 转换为 text content block delta。
- reasoning delta 转换为 thinking delta。
- function call item 转换为 tool_use content block。
- 当上游流只发送 completed output 时，使用最终 output 作为兜底。
- Responses cached tokens 转换回 Anthropic `cache_read_input_tokens`。

## 当前已知边界

以下是有意保留或当前接受的有损区域：

- Chat 专有且没有 Responses 等价物的参数不会被虚构进 IR。
- 转换到 Chat Completions 时，会丢弃非 function 类型的 Responses tools。
- Responses web search output 不会在 Anthropic 或 Chat 输出中表示。
- OpenAI OAuth Codex 端点兼容性要求移除内部端点不接受的公共 Responses 字段。
- Anthropic 专有的 `top_k` 没有 OpenAI Responses 等价物，不会透传到 OpenAI 上游请求。

未来变更应保留以下规则：

- 不要在转换器中根据模型名称推断模型能力。
- 不要在转换器中截断 token 限制。
- 除非客户端或路由明确要求，否则不要强制流式响应。
- 内部专用字段，例如 `_landgate_stop_sequences`，不得出现在直接发送给 Responses 上游的请求中。
