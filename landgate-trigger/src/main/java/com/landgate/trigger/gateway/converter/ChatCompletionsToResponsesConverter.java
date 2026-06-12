package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI Chat Completions API → OpenAI Responses API（IR）转换器。
 * <p>
 * 负责请求转换、非流式响应转换、流式 SSE 翻译。
 * 由 {@link ChatCompletionsConverter} 门面委托调用，不作为独立 Spring Bean。
 *
 * <p>参照：sub2api {@code chatcompletions_to_responses.go} + {@code chatcompletions_responses_bridge.go}
 */
@Slf4j
public class ChatCompletionsToResponsesConverter {

    private static final ObjectMapper JSON = new ObjectMapper();
    // ========================
    // 请求转换：Chat Completions → Responses IR
    // ========================

    /**
     * 将 Chat Completions 请求转换为 Responses API 请求（IR）。
     */
    public JsonNode requestToIR(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            // --- 基础字段 ---
            copyTextIfExists(src, dst, "model");
            copyTextIfExists(src, dst, "instructions");
            copyTextIfExists(src, dst, "service_tier");
            copyObjectIfExists(src, dst, "metadata");
            copyBooleanIfExists(src, dst, "parallel_tool_calls");
            copyTextIfExists(src, dst, "user");
            copyTextIfExists(src, dst, "safety_identifier");
            copyTextIfExists(src, dst, "prompt_cache_key");
            copyTextIfExists(src, dst, "prompt_cache_retention");
            if (isValidTopLogprobs(src.get("top_logprobs"))) {
                dst.set("top_logprobs", src.get("top_logprobs"));
            }

            // 协议转换层不根据模型名猜能力；采样参数按客户端显式请求保留。
            copyNumberIfExists(src, dst, "temperature");
            copyNumberIfExists(src, dst, "top_p");

            // response_format / verbosity → text
            if (src.has("response_format") && src.get("response_format").isObject()) {
                JsonNode format = convertChatResponseFormatToResponses(src.get("response_format"));
                if (format != null) {
                    ObjectNode text = dst.has("text") && dst.get("text").isObject()
                            ? (ObjectNode) dst.get("text")
                            : JSON.createObjectNode();
                    text.set("format", format);
                    dst.set("text", text);
                }
            }
            String verbosity = normalizeVerbosity(src.get("verbosity"));
            if (verbosity != null) {
                ObjectNode text = dst.has("text") && dst.get("text").isObject()
                        ? (ObjectNode) dst.get("text")
                        : JSON.createObjectNode();
                text.put("verbosity", verbosity);
                dst.set("text", text);
            }

            // max_tokens / max_completion_tokens → max_output_tokens（后者优先）
            JsonNode maxTokensNode = null;
            if (src.has("max_completion_tokens")) {
                maxTokensNode = src.get("max_completion_tokens");
            } else if (src.has("max_tokens")) {
                maxTokensNode = src.get("max_tokens");
            }
            if (isPositiveInt(maxTokensNode)) {
                dst.put("max_output_tokens", maxTokensNode.asInt());
            }

            // stream：保留客户端语义；未传时让 Responses 使用官方默认的非流式。
            copyBooleanIfExists(src, dst, "stream");

            // stop → 内部 IR 扩展。Responses 上游不直接消费，跨协议转 Anthropic/Chat 时再还原。
            if (src.has("stop") && !src.get("stop").isNull()) {
                JsonNode stop = normalizeStopSequences(src.get("stop"));
                if (stop != null) dst.set("_landgate_stop_sequences", stop);
            }

            // reasoning_effort → reasoning
            String reasoningEffort = normalizeChatReasoningEffort(src.get("reasoning_effort"));
            if (reasoningEffort != null) {
                ObjectNode reasoning = JSON.createObjectNode();
                reasoning.put("effort", reasoningEffort);
                reasoning.put("summary", "auto");
                dst.set("reasoning", reasoning);
            }

            // --- messages → input ---
            ArrayNode input = JSON.createArrayNode();
            if (src.has("messages") && src.get("messages").isArray()) {
                for (JsonNode msg : src.get("messages")) {
                    String role = msg.has("role") ? msg.get("role").asText() : "user";
                    JsonNode contentNode = msg.get("content");

                    switch (role) {
                        case "system" -> convertChatInstructionMessage("system", contentNode, input);
                        case "developer" -> convertChatInstructionMessage("developer", contentNode, input);
                        case "user" -> convertChatUserMessage(contentNode, input);
                        case "assistant" -> convertChatAssistantMessage(msg, input);
                        case "tool" -> convertChatToolMessage(msg, input);
                        case "function" -> convertChatFunctionMessage(msg, input);
                        default -> convertChatUserMessage(contentNode, input);
                    }
                }
            }
            dst.set("input", input);

            // --- tools ---
            ArrayNode responsesTools = JSON.createArrayNode();
            if (src.has("tools") && src.get("tools").isArray()) {
                for (JsonNode tool : src.get("tools")) {
                    String type = tool.has("type") ? tool.get("type").asText() : "";
                    if ("function".equals(type) && tool.has("function")) {
                        JsonNode func = tool.get("function");
                        if (isBlankText(func.get("name"))) {
                            continue;
                        }
                        ObjectNode rt = JSON.createObjectNode();
                        rt.put("type", "function");
                        rt.put("name", func.get("name").asText());
                        copyTextIfExists(func, rt, "description");
                        if (func.has("parameters")) rt.set("parameters", normalizeToolParameters(func.get("parameters")));
                        rt.put("strict", func.has("strict") && func.get("strict").isBoolean()
                                && func.get("strict").asBoolean());
                        responsesTools.add(rt);
                    } else if ("custom".equals(type) && tool.has("custom") && tool.get("custom").isObject()) {
                        JsonNode custom = tool.get("custom");
                        if (isBlankText(custom.get("name"))) {
                            continue;
                        }
                        ObjectNode rt = JSON.createObjectNode();
                        rt.put("type", "custom");
                        rt.put("name", custom.get("name").asText());
                        copyTextIfExists(custom, rt, "description");
                        JsonNode format = convertChatCustomToolFormatToResponses(custom.get("format"));
                        if (format != null) {
                            rt.set("format", format);
                        }
                        responsesTools.add(rt);
                    }
                }
            }
            // 旧式 functions[] → tools
            if (src.has("functions") && src.get("functions").isArray()) {
                for (JsonNode func : src.get("functions")) {
                    if (isBlankText(func.get("name"))) {
                        continue;
                    }
                    ObjectNode rt = JSON.createObjectNode();
                    rt.put("type", "function");
                    rt.put("name", func.get("name").asText());
                    copyTextIfExists(func, rt, "description");
                    if (func.has("parameters")) rt.set("parameters", normalizeToolParameters(func.get("parameters")));
                    rt.put("strict", false);
                    responsesTools.add(rt);
                }
            }
            if (responsesTools.size() > 0) {
                dst.set("tools", responsesTools);
            }
            if (src.has("web_search_options") && src.get("web_search_options").isObject()) {
                ObjectNode webSearchTool = convertChatWebSearchOptionsToResponsesTool(src.get("web_search_options"));
                if (webSearchTool != null) {
                    responsesTools.add(webSearchTool);
                    dst.set("tools", responsesTools);
                }
            }

            // --- tool_choice ---
            if (src.has("tool_choice") && !src.get("tool_choice").isNull()) {
                JsonNode toolChoice = convertChatToolChoiceToResponses(src.get("tool_choice"));
                if (toolChoice != null) {
                    dst.set("tool_choice", toolChoice);
                }
            } else if (src.has("function_call") && !src.get("function_call").isNull()) {
                // 旧式 function_call → tool_choice
                JsonNode toolChoice = convertLegacyFunctionCall(src.get("function_call"));
                if (toolChoice != null) {
                    dst.set("tool_choice", toolChoice);
                }
            }

            // --- 固定字段 ---
            if (src.has("store") && src.get("store").isBoolean()) {
                copyBooleanIfExists(src, dst, "store");
            } else {
                dst.put("store", false);
            }
            ArrayNode include = JSON.createArrayNode();
            if (reasoningEffort != null) {
                include.add("reasoning.encrypted_content");
            }
            if (src.has("logprobs") && src.get("logprobs").isBoolean() && src.get("logprobs").asBoolean()) {
                include.add("message.output_text.logprobs");
            }
            if (!include.isEmpty()) {
                dst.set("include", include);
            }

            return dst;
        } catch (Exception e) {
            log.warn("ChatCompletions→Responses requestToIR error: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    // ========================
    // 非流式响应转换：Chat Completions → Responses IR
    // ========================

    /**
     * 将 Chat Completions 非流式响应转换为 Responses 响应（IR）。
     */
    public JsonNode responseToIR(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            dst.put("id", textOrDefault(src.get("id"),
                    "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24)));
            dst.put("object", "response");
            dst.put("model", isBlankText(src.get("model")) ? "unknown" : src.get("model").asText());
            if (isNonNegativeLong(src.get("created"))) {
                dst.put("created_at", src.get("created").asLong());
            }

            if (!src.has("choices") || src.get("choices").size() == 0) {
                // 无 choices → 空响应
                dst.put("status", "completed");
                dst.set("output", JSON.createArrayNode());
                return dst;
            }

            JsonNode choice = src.get("choices").get(0);
            JsonNode message = choice.has("message") ? choice.get("message") : null;
            String finishReason = textOrDefault(choice.get("finish_reason"), "stop");

            ArrayNode output = JSON.createArrayNode();
            boolean hasToolCalls = false;

            if (message != null) {
                // reasoning_content → reasoning output item
                String reasoningContent = textOrDefault(message.get("reasoning_content"), "");
                if (!reasoningContent.isEmpty()) {
                    ObjectNode reasoningItem = JSON.createObjectNode();
                    reasoningItem.put("type", "reasoning");
                    reasoningItem.put("status", "completed");
                    ArrayNode summary = JSON.createArrayNode();
                    ObjectNode summaryText = JSON.createObjectNode();
                    summaryText.put("type", "summary_text");
                    summaryText.put("text", reasoningContent);
                    summary.add(summaryText);
                    reasoningItem.set("summary", summary);
                    output.add(reasoningItem);
                }

                // content/refusal → output_text/refusal
                String contentText = textOrDefault(message.get("content"), "");
                String refusalText = textOrDefault(message.get("refusal"), "");

                // tool_calls / legacy function_call → function_call output items
                ArrayNode toolCalls = null;
                if (message.has("tool_calls") && message.get("tool_calls").isArray()) {
                    toolCalls = (ArrayNode) message.get("tool_calls");
                    for (JsonNode tc : toolCalls) {
                        if (isBlankText(tc.get("id"))) {
                            log.debug("Chat→Responses: tool_call missing id, ignored");
                            continue;
                        }
                        ObjectNode funcItem = JSON.createObjectNode();
                        funcItem.put("call_id", tc.get("id").asText());
                        funcItem.put("status", "completed");
                        if ("custom".equals(tc.path("type").asText("")) && tc.has("custom")) {
                            JsonNode custom = tc.get("custom");
                            if (isBlankText(custom.get("name"))) {
                                log.debug("Chat→Responses: custom tool_call missing name, ignored");
                                continue;
                            }
                            funcItem.put("type", "custom_tool_call");
                            funcItem.put("name", custom.get("name").asText());
                            funcItem.put("input", textOrDefault(custom.get("input"), ""));
                        } else if (tc.has("function")) {
                            JsonNode func = tc.get("function");
                            if (isBlankText(func.get("name"))) {
                                log.debug("Chat→Responses: function tool_call missing name, ignored");
                                continue;
                            }
                            funcItem.put("type", "function_call");
                            funcItem.put("name", func.get("name").asText());
                            String args = textOrDefault(func.get("arguments"), "{}");
                            funcItem.put("arguments", args.isEmpty() ? "{}" : args);
                        } else {
                            log.debug("Chat→Responses: tool_call missing function/custom payload, ignored");
                            continue;
                        }
                        output.add(funcItem);
                        hasToolCalls = true;
                    }
                } else if (message.has("function_call") && message.get("function_call").isObject()) {
                    JsonNode fc = message.get("function_call");
                    if (isBlankText(fc.get("name"))) {
                        log.debug("Chat→Responses: legacy function_call missing name, ignored");
                    } else {
                        ObjectNode funcItem = JSON.createObjectNode();
                        funcItem.put("type", "function_call");
                        String name = fc.get("name").asText();
                        funcItem.put("call_id", name);
                        funcItem.put("status", "completed");
                        funcItem.put("name", name);
                        String args = textOrDefault(fc.get("arguments"), "{}");
                        funcItem.put("arguments", args.isEmpty() ? "{}" : args);
                        output.add(funcItem);
                        hasToolCalls = true;
                    }
                }

                // message item（包含文本内容）。纯 tool/reasoning 响应不伪造空文本 message。
                boolean hasMessagePayload = (refusalText != null && !refusalText.isEmpty())
                        || (contentText != null && !contentText.isEmpty());
                if (hasMessagePayload) {
                    ObjectNode msgItem = JSON.createObjectNode();
                    msgItem.put("type", "message");
                    msgItem.put("role", "assistant");
                    msgItem.put("status", "completed");
                    ArrayNode msgContent = JSON.createArrayNode();
                    ObjectNode textPart = JSON.createObjectNode();
                    if (refusalText != null && !refusalText.isEmpty()) {
                        textPart.put("type", "refusal");
                        textPart.put("refusal", refusalText);
                    } else {
                        textPart.put("type", "output_text");
                        textPart.put("text", contentText != null ? contentText : "");
                    }
                    msgContent.add(textPart);
                    msgItem.set("content", msgContent);
                    output.add(msgItem);
                }
            }

            dst.set("output", output);

            // finish_reason → status
            String status = mapChatFinishReasonToResponsesStatus(finishReason);
            dst.put("status", status);
            if ("incomplete".equals(status)) {
                ObjectNode incompleteDetails = JSON.createObjectNode();
                incompleteDetails.put("reason", mapChatFinishReasonToResponsesIncompleteReason(finishReason));
                dst.set("incomplete_details", incompleteDetails);
            }

            // usage
            if (src.has("usage")) {
                JsonNode usage = src.get("usage");
                ObjectNode respUsage = JSON.createObjectNode();
                int inputTokens = nonNegativeIntOrZero(usage.get("prompt_tokens"));
                int outputTokens = nonNegativeIntOrZero(usage.get("completion_tokens"));
                respUsage.put("input_tokens", inputTokens);
                respUsage.put("output_tokens", outputTokens);
                int totalTokens = isNonNegativeInt(usage.get("total_tokens"))
                        ? usage.get("total_tokens").asInt()
                        : inputTokens + outputTokens;
                respUsage.put("total_tokens", totalTokens);
                if (isPositiveInt(usage.path("prompt_tokens_details").get("cached_tokens"))) {
                    ObjectNode details = JSON.createObjectNode();
                    details.put("cached_tokens", usage.get("prompt_tokens_details").get("cached_tokens").asInt());
                    respUsage.set("input_tokens_details", details);
                }
                if (isPositiveInt(usage.path("completion_tokens_details").get("reasoning_tokens"))) {
                    ObjectNode details = JSON.createObjectNode();
                    details.put("reasoning_tokens", usage.get("completion_tokens_details").get("reasoning_tokens").asInt());
                    respUsage.set("output_tokens_details", details);
                }
                dst.set("usage", respUsage);
            }

            return dst;
        } catch (Exception e) {
            log.warn("ChatCompletions→Responses responseToIR error: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    // ========================
    // 流式 SSE 翻译：Chat SSE → Responses SSE（IR）
    // ========================

    /**
     * 创建 Chat Completions SSE → Responses SSE 流式翻译器。
     */
    public StreamTranslator createStreamToIR(String model) {
        return new ChatToIRStreamTranslator(model);
    }

    // ========================
    // 流式翻译器内部类
    // ========================

    /**
     * Chat Completions SSE → Responses SSE 事件翻译器。
     */
    static class ChatToIRStreamTranslator implements StreamTranslator {

        private boolean done = false;
        private boolean createdSent = false;
        private boolean outputItemAdded = false;
        private final String responseId;
        private String model;
        private long createdAt;
        private long sequenceNumber = 0;
        private int outputIndex = 0;
        private int contentIndex = 0;
        private String currentItemId;
        private String currentItemType = "message";
        private String currentCallId = "";
        private String currentFunctionName = "";
        private final StringBuilder currentFunctionArguments = new StringBuilder();

        private boolean hasToolCalls = false;
        private String finishReason = "stop";
        private int nextToolCallIndex = 0;
        private final List<String> pendingToolCallIds = new ArrayList<>();
        private final List<String> pendingToolCallNames = new ArrayList<>();
        private final Map<Integer, ToolCallState> toolCallStates = new LinkedHashMap<>();

        private int inputTokens = 0;
        private int outputTokens = 0;
        private int cachedTokens = 0;
        private int reasoningTokens = 0;

        ChatToIRStreamTranslator(String model) {
            this.model = model != null ? model : "unknown";
            this.responseId = "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            this.createdAt = System.currentTimeMillis() / 1000;
            this.currentItemId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }

        private static class ToolCallState {
            final int outputIndex;
            final String itemId;
            String callId;
            String name;
            final StringBuilder arguments = new StringBuilder();
            boolean done = false;

            ToolCallState(int outputIndex, String itemId, String callId, String name) {
                this.outputIndex = outputIndex;
                this.itemId = itemId;
                this.callId = callId;
                this.name = name;
            }
        }

        @Override
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (done || line == null || line.isBlank()) return output;

            if ("data: [DONE]".equals(line)) {
                // 流结束：关闭 open item，发送 completed
                closeOpenItem(output);
                closeOpenToolCalls(output);
                appendCompleted(output);
                done = true;
                return output;
            }

            if (!line.startsWith("data: ")) return output;
            String json = line.substring(6);

            try {
                JsonNode chunk = JSON.readTree(json);
                if (isNonNegativeLong(chunk.get("created"))) {
                    createdAt = chunk.get("created").asLong();
                }
                if (!chunk.has("choices") || chunk.get("choices").size() == 0) {
                    // usage-only chunk
                    if (chunk.has("usage")) {
                        extractUsage(chunk.get("usage"));
                    }
                    return output;
                }

                JsonNode choice = chunk.get("choices").get(0);
                JsonNode delta = choice.has("delta") ? choice.get("delta") : null;

                if (chunk.has("usage")) {
                    extractUsage(chunk.get("usage"));
                }

                // role → 只触发 response.created；具体 output item 等到文本、推理或工具调用出现时再创建。
                if (delta != null && delta.has("role")) {
                    ensureCreatedSent(output);
                }

                // content → output_text.delta
                if (delta != null && delta.has("content") && !delta.get("content").isNull()) {
                    String text = textOrDefault(delta.get("content"), "");
                    if (!text.isEmpty()) {
                        if (outputItemAdded && !"message".equals(currentItemType)) {
                            if (closeOpenItem(output)) outputIndex++;
                        }
                        if (!outputItemAdded) {
                            ensureCreatedSent(output);
                            currentItemId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                            appendEvent(output, "response.output_item.added",
                                    fmt("{\"type\":\"response.output_item.added\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":%d,\"item\":{\"id\":\"%s\",\"type\":\"message\",\"status\":\"in_progress\",\"role\":\"assistant\",\"content\":[]}}",
                                            sequenceNumber++, responseId, outputIndex, currentItemId));
                            outputItemAdded = true;
                            currentItemType = "message";
                        }
                        appendEvent(output, "response.output_text.delta",
                                fmt("{\"type\":\"response.output_text.delta\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"content_index\":%d,\"delta\":\"%s\"}",
                                        sequenceNumber++, responseId, currentItemId, outputIndex, contentIndex,
                                        escapeJsonValue(text)));
                    }
                }

                // refusal → refusal.delta
                if (delta != null && delta.has("refusal") && !delta.get("refusal").isNull()) {
                    String refusal = textOrDefault(delta.get("refusal"), "");
                    if (!refusal.isEmpty()) {
                        if (outputItemAdded && !"message".equals(currentItemType)) {
                            if (closeOpenItem(output)) outputIndex++;
                        }
                        if (!outputItemAdded) {
                            ensureCreatedSent(output);
                            currentItemId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                            appendEvent(output, "response.output_item.added",
                                    fmt("{\"type\":\"response.output_item.added\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":%d,\"item\":{\"id\":\"%s\",\"type\":\"message\",\"status\":\"in_progress\",\"role\":\"assistant\",\"content\":[]}}",
                                            sequenceNumber++, responseId, outputIndex, currentItemId));
                            outputItemAdded = true;
                            currentItemType = "message";
                        }
                        appendEvent(output, "response.refusal.delta",
                                fmt("{\"type\":\"response.refusal.delta\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"content_index\":%d,\"delta\":\"%s\"}",
                                        sequenceNumber++, responseId, currentItemId, outputIndex, contentIndex,
                                        escapeJsonValue(refusal)));
                    }
                }

                // reasoning_content → reasoning_summary_text.delta
                if (delta != null && delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
                    String rc = textOrDefault(delta.get("reasoning_content"), "");
                    if (!rc.isEmpty()) {
                        if (outputItemAdded && !"reasoning".equals(currentItemType)) {
                            if (closeOpenItem(output)) outputIndex++;
                        }
                        if (!outputItemAdded) {
                            ensureCreatedSent(output);
                            currentItemId = "rsn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                            appendEvent(output, "response.output_item.added",
                                    fmt("{\"type\":\"response.output_item.added\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":%d,\"item\":{\"id\":\"%s\",\"type\":\"reasoning\",\"status\":\"in_progress\",\"summary\":[]}}",
                                            sequenceNumber++, responseId, outputIndex, currentItemId));
                            outputItemAdded = true;
                            currentItemType = "reasoning";
                        }
                        appendEvent(output, "response.reasoning_summary_text.delta",
                                fmt("{\"type\":\"response.reasoning_summary_text.delta\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"summary_index\":0,\"delta\":\"%s\"}",
                                        sequenceNumber++, responseId, currentItemId, outputIndex,
                                        escapeJsonValue(rc)));
                    }
                }

                // tool_calls → output_item.added + function_call_arguments.delta
                // Chat 流式工具调用可按 index 并行/交错输出，因此每个 index 独立累积参数。
                if (delta != null && delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
                    for (JsonNode tc : delta.get("tool_calls")) {
                        int tcIndex = nonNegativeIntOrDefault(tc.get("index"), nextToolCallIndex);

                        // 新增 tool_call
                        if (tc.has("id") && tc.has("function") && tc.get("function").has("name")) {
                            String callId = textOrDefault(tc.get("id"), "");
                            String funcName = textOrDefault(tc.get("function").get("name"), "");
                            if (callId.isBlank() || funcName.isBlank()) {
                                log.debug("Chat→Responses stream: tool_call missing id or name, ignored");
                                continue;
                            }

                            // 确保列表中已有此 index 的位置
                            while (pendingToolCallIds.size() <= tcIndex) {
                                pendingToolCallIds.add(null);
                                pendingToolCallNames.add(null);
                            }
                            pendingToolCallIds.set(tcIndex, callId);
                            pendingToolCallNames.set(tcIndex, funcName);
                            hasToolCalls = true;

                            ensureToolCallState(output, tcIndex, callId, funcName);
                            nextToolCallIndex = tcIndex + 1;
                        }

                        // function.arguments → function_call_arguments.delta
                        if (tc.has("function") && tc.get("function").has("arguments")) {
                            String args = textOrDefault(tc.get("function").get("arguments"), "");
                            if (!args.isEmpty()) {
                                ToolCallState state = toolCallStates.get(tcIndex);
                                if (state == null) {
                                    log.debug("Chat→Responses stream: tool_call arguments without valid tool_call ignored");
                                    continue;
                                }
                                state.arguments.append(args);
                                appendEvent(output, "response.function_call_arguments.delta",
                                        fmt("{\"type\":\"response.function_call_arguments.delta\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"delta\":\"%s\"}",
                                                sequenceNumber++, responseId, state.itemId, state.outputIndex,
                                                escapeJsonValue(args)));
                            }
                        }
                    }
                }

                // legacy function_call → function_call item
                if (delta != null && delta.has("function_call") && delta.get("function_call").isObject()) {
                    JsonNode fc = delta.get("function_call");
                    int tcIndex = 0;
                    String funcName = textOrDefault(fc.get("name"), "");
                    ToolCallState state = toolCallStates.get(tcIndex);
                    if (!funcName.isBlank()) {
                        state = ensureToolCallState(output, tcIndex, funcName, funcName);
                        hasToolCalls = true;
                    } else if (state == null) {
                        log.debug("Chat→Responses stream: legacy function_call missing name, ignored");
                        return output;
                    }
                    if (fc.has("arguments")) {
                        String args = textOrDefault(fc.get("arguments"), "");
                        if (!args.isEmpty()) {
                            state.arguments.append(args);
                            appendEvent(output, "response.function_call_arguments.delta",
                                    fmt("{\"type\":\"response.function_call_arguments.delta\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"delta\":\"%s\"}",
                                            sequenceNumber++, responseId, state.itemId, state.outputIndex,
                                            escapeJsonValue(args)));
                        }
                    }
                }

                // finish_reason → 关闭当前 item；等待后续 usage-only chunk 和最终 [DONE] 再完成响应。
                if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()
                        && choice.get("finish_reason").isTextual()
                        && !"null".equals(choice.get("finish_reason").asText())) {
                    finishReason = choice.get("finish_reason").asText();
                    closeOpenItem(output);
                    closeOpenToolCalls(output);
                }
            } catch (Exception e) {
                log.debug("Chat→IR SSE error: {}", e.getMessage());
            }
            return output;
        }

        private ToolCallState ensureToolCallState(List<String> output, int tcIndex, String callId, String funcName) {
            ToolCallState existing = toolCallStates.get(tcIndex);
            if (existing != null) {
                if (existing.callId == null || existing.callId.isBlank() || existing.callId.startsWith("call_")) {
                    existing.callId = callId;
                }
                if (existing.name == null || existing.name.isBlank()) {
                    existing.name = funcName;
                }
                return existing;
            }

            if (outputItemAdded) {
                if (closeOpenItem(output)) outputIndex++;
            }

            String itemId = "item_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            ToolCallState state = new ToolCallState(outputIndex++, itemId, callId, funcName);
            toolCallStates.put(tcIndex, state);

            ensureCreatedSent(output);
            appendEvent(output, "response.output_item.added",
                    fmt("{\"type\":\"response.output_item.added\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":%d,\"item\":{\"id\":\"%s\",\"type\":\"function_call\",\"call_id\":\"%s\",\"name\":\"%s\",\"status\":\"in_progress\"}}",
                            sequenceNumber++, responseId, state.outputIndex, state.itemId,
                            escapeJsonValue(state.callId), escapeJsonValue(state.name)));
            return state;
        }

        private void closeOpenToolCalls(List<String> output) {
            for (ToolCallState state : toolCallStates.values()) {
                if (state.done) continue;
                String args = state.arguments.length() > 0 ? state.arguments.toString() : "{}";
                appendEvent(output, "response.function_call_arguments.done",
                        fmt("{\"type\":\"response.function_call_arguments.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"arguments\":\"%s\"}",
                                sequenceNumber++, responseId, state.itemId, state.outputIndex,
                                escapeJsonValue(args)));
                appendEvent(output, "response.output_item.done",
                        fmt("{\"type\":\"response.output_item.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":%d,\"item\":{\"id\":\"%s\",\"type\":\"function_call\",\"call_id\":\"%s\",\"name\":\"%s\",\"arguments\":\"%s\",\"status\":\"completed\"}}",
                                sequenceNumber++, responseId, state.outputIndex, state.itemId,
                                escapeJsonValue(state.callId), escapeJsonValue(state.name),
                                escapeJsonValue(args)));
                state.done = true;
            }
        }

        private void ensureCreatedSent(List<String> output) {
            if (!createdSent) {
                appendEvent(output, "response.created",
                        fmt("{\"type\":\"response.created\",\"sequence_number\":%d,\"response\":{\"id\":\"%s\",\"object\":\"response\",\"model\":\"%s\",\"created_at\":%d,\"status\":\"in_progress\",\"output\":[],\"usage\":null}}",
                                sequenceNumber++, responseId, escapeJsonValue(model), createdAt));
                createdSent = true;
            }
        }

        private boolean closeOpenItem(List<String> output) {
            if (outputItemAdded) {
                if ("function_call".equals(currentItemType)) {
                    String args = currentFunctionArguments.length() > 0 ? currentFunctionArguments.toString() : "{}";
                    appendEvent(output, "response.function_call_arguments.done",
                            fmt("{\"type\":\"response.function_call_arguments.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"arguments\":\"%s\"}",
                                    sequenceNumber++, responseId, currentItemId, outputIndex,
                                    escapeJsonValue(args)));
                    appendEvent(output, "response.output_item.done",
                            fmt("{\"type\":\"response.output_item.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":%d,\"item\":{\"id\":\"%s\",\"type\":\"function_call\",\"call_id\":\"%s\",\"name\":\"%s\",\"arguments\":\"%s\",\"status\":\"completed\"}}",
                                    sequenceNumber++, responseId, outputIndex, currentItemId,
                                    escapeJsonValue(currentCallId), escapeJsonValue(currentFunctionName),
                                    escapeJsonValue(args)));
                } else if ("reasoning".equals(currentItemType)) {
                    appendEvent(output, "response.reasoning_summary_text.done",
                            fmt("{\"type\":\"response.reasoning_summary_text.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"item_id\":\"%s\",\"output_index\":%d,\"summary_index\":0}",
                                    sequenceNumber++, responseId, currentItemId, outputIndex));
                    appendEvent(output, "response.output_item.done",
                            fmt("{\"type\":\"response.output_item.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":%d,\"item\":{\"id\":\"%s\",\"type\":\"reasoning\",\"status\":\"completed\"}}",
                                    sequenceNumber++, responseId, outputIndex, currentItemId));
                } else {
                    appendEvent(output, "response.output_item.done",
                            fmt("{\"type\":\"response.output_item.done\",\"sequence_number\":%d,\"response_id\":\"%s\",\"output_index\":%d,\"item\":{\"id\":\"%s\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\"}}",
                                    sequenceNumber++, responseId, outputIndex, currentItemId));
                }
                outputItemAdded = false;
                currentItemType = "message";
                currentCallId = "";
                currentFunctionName = "";
                currentFunctionArguments.setLength(0);
                return true;
            }
            return false;
        }

        private void appendCompleted(List<String> output) {
            String status = isIncompleteFinishReason(finishReason) ? "incomplete" : "completed";
            String incompleteDetails = isIncompleteFinishReason(finishReason)
                    ? fmt(",\"incomplete_details\":{\"reason\":\"%s\"}",
                            mapChatFinishReasonToResponsesIncompleteReason(finishReason))
                    : "";
            String cacheDetails = cachedTokens > 0
                    ? fmt(",\"input_tokens_details\":{\"cached_tokens\":%d}", cachedTokens)
                    : "";
            String outputDetails = reasoningTokens > 0
                    ? fmt(",\"output_tokens_details\":{\"reasoning_tokens\":%d}", reasoningTokens)
                    : "";
            String eventType = "incomplete".equals(status) ? "response.incomplete" : "response.completed";
            appendEvent(output, eventType,
                    fmt("{\"type\":\"%s\",\"sequence_number\":%d,\"response\":{\"id\":\"%s\",\"object\":\"response\",\"model\":\"%s\",\"created_at\":%d,\"status\":\"%s\"%s,\"usage\":{\"input_tokens\":%d,\"output_tokens\":%d,\"total_tokens\":%d%s%s}}}",
                            eventType, sequenceNumber++, responseId, escapeJsonValue(model), createdAt, status, incompleteDetails,
                            inputTokens, outputTokens, inputTokens + outputTokens, cacheDetails, outputDetails));
        }

        private void extractUsage(JsonNode usage) {
            if (isNonNegativeInt(usage.get("prompt_tokens"))) inputTokens = usage.get("prompt_tokens").asInt();
            if (isNonNegativeInt(usage.get("completion_tokens"))) outputTokens = usage.get("completion_tokens").asInt();
            if (usage.has("prompt_tokens_details")
                    && isNonNegativeInt(usage.get("prompt_tokens_details").get("cached_tokens"))) {
                cachedTokens = usage.get("prompt_tokens_details").get("cached_tokens").asInt();
            }
            if (usage.has("completion_tokens_details")
                    && isNonNegativeInt(usage.get("completion_tokens_details").get("reasoning_tokens"))) {
                reasoningTokens = usage.get("completion_tokens_details").get("reasoning_tokens").asInt();
            }
        }

        @Override public boolean isDone() { return done; }
        @Override public int getInputTokens() { return inputTokens; }
        @Override public int getOutputTokens() { return outputTokens; }
    }

    // ========================
    // 辅助方法：消息转换
    // ========================

    /**
     * System 消息 → system role input item。
     */
    private static void convertChatInstructionMessage(String role, JsonNode contentNode, ArrayNode input) {
        ObjectNode item = JSON.createObjectNode();
        item.put("role", role);

        if (contentNode == null) {
            item.put("content", "");
        } else if (contentNode.isTextual()) {
            item.put("content", contentNode.asText());
        } else if (contentNode.isArray()) {
            // 数组格式 → 提取文本
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : contentNode) {
                if ("text".equals(textOrDefault(part.get("type"), ""))
                        && !isBlankText(part.get("text"))) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(part.get("text").asText());
                }
            }
            if (sb.length() == 0) {
                return;
            }
            item.put("content", sb.toString());
        } else {
            return;
        }
        input.add(item);
    }

    /**
     * User 消息 → user role input item。
     */
    private static void convertChatUserMessage(JsonNode contentNode, ArrayNode input) {
        ObjectNode item = JSON.createObjectNode();
        item.put("role", "user");

        if (contentNode == null) {
            item.put("content", "");
        } else if (contentNode.isTextual()) {
            item.put("content", contentNode.asText());
        } else if (contentNode.isArray()) {
            ArrayNode parts = JSON.createArrayNode();
            for (JsonNode part : contentNode) {
                String partType = textOrDefault(part.get("type"), "");
                switch (partType) {
                    case "text" -> {
                        if (isBlankText(part.get("text"))) {
                            break;
                        }
                        ObjectNode p = JSON.createObjectNode();
                        p.put("type", "input_text");
                        p.put("text", part.get("text").asText());
                        parts.add(p);
                    }
                    case "image_url" -> {
                        JsonNode imageUrl = part.get("image_url");
                        if (imageUrl != null && imageUrl.isObject() && !isBlankText(imageUrl.get("url"))) {
                            String url = imageUrl.get("url").asText();
                            if (!isEmptyBase64DataURI(url)) {
                                ObjectNode p = JSON.createObjectNode();
                                p.put("type", "input_image");
                                p.put("image_url", url);
                                String detail = normalizeImageDetail(imageUrl.get("detail"));
                                if (detail != null) {
                                    p.put("detail", detail);
                                }
                                parts.add(p);
                            }
                        }
                    }
                    case "input_audio" -> {
                        JsonNode inputAudio = part.get("input_audio");
                        if (inputAudio != null && inputAudio.isObject() && hasChatAudioPayload(inputAudio)) {
                            ObjectNode p = JSON.createObjectNode();
                            p.put("type", "input_audio");
                            p.set("input_audio", inputAudio);
                            parts.add(p);
                        }
                    }
                    case "file" -> {
                        JsonNode file = part.get("file");
                        if (file != null && file.isObject() && hasChatFilePayload(file)) {
                            ObjectNode p = JSON.createObjectNode();
                            p.put("type", "input_file");
                            copyTextIfExists(file, p, "file_data");
                            copyTextIfExists(file, p, "file_id");
                            copyTextIfExists(file, p, "filename");
                            parts.add(p);
                        }
                    }
                    default ->
                        log.debug("Chat→Responses: unknown content part type '{}'", partType);
                }
            }
            if (parts.size() == 0) {
                return;
            }
            item.set("content", parts);
        }
        input.add(item);
    }

    /**
     * Assistant 消息 → assistant message item + function_call items。
     */
    private static void convertChatAssistantMessage(JsonNode msg, ArrayNode input) {
        // 解析 content。Chat 官方 assistant content part 不包含 thinking/reasoning；
        // 兼容客户端传入这类扩展时，拆到 Responses reasoning item，避免污染 output_text。
        ParsedAssistantContent parsedContent = parseAssistantContent(msg.get("content"));
        String contentText = parsedContent.text();
        String reasoningContent = textOrDefault(msg.get("reasoning_content"), "");

        StringBuilder fullContent = new StringBuilder();
        if (contentText != null && !contentText.isEmpty()) {
            fullContent.append(contentText);
        }

        // assistant message item（非空文本时）
        if (fullContent.length() > 0) {
            ObjectNode item = JSON.createObjectNode();
            item.put("type", "message");
            item.put("role", "assistant");
            ArrayNode parts = JSON.createArrayNode();
            ObjectNode part = JSON.createObjectNode();
            part.put("type", "output_text");
            part.put("text", fullContent.toString());
            parts.add(part);
            item.set("content", parts);
            input.add(item);
        }

        for (String reasoningText : parsedContent.reasoningTexts()) {
            input.add(convertChatReasoningContentToResponsesReasoning(reasoningText));
        }
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            input.add(convertChatReasoningContentToResponsesReasoning(reasoningContent));
        }

        // tool_calls → function_call/custom_tool_call items
        if (msg.has("tool_calls") && msg.get("tool_calls").isArray()) {
            for (JsonNode tc : msg.get("tool_calls")) {
                if (isBlankText(tc.get("id"))) {
                    log.debug("Chat→Responses request: tool_call missing id, ignored");
                    continue;
                }
                ObjectNode toolItem = JSON.createObjectNode();
                toolItem.put("call_id", tc.get("id").asText());
                if ("custom".equals(tc.path("type").asText("")) && tc.has("custom")) {
                    JsonNode custom = tc.get("custom");
                    if (isBlankText(custom.get("name"))) {
                        log.debug("Chat→Responses request: custom tool_call missing name, ignored");
                        continue;
                    }
                    toolItem.put("type", "custom_tool_call");
                    toolItem.put("name", custom.get("name").asText());
                    toolItem.put("input", textOrDefault(custom.get("input"), ""));
                } else if (tc.has("function")) {
                    JsonNode func = tc.get("function");
                    if (isBlankText(func.get("name"))) {
                        log.debug("Chat→Responses request: function tool_call missing name, ignored");
                        continue;
                    }
                    toolItem.put("type", "function_call");
                    toolItem.put("name", func.get("name").asText());
                    String args = textOrDefault(func.get("arguments"), "{}");
                    toolItem.put("arguments", args.isEmpty() ? "{}" : args);
                } else {
                    log.debug("Chat→Responses request: tool_call missing function/custom payload, ignored");
                    continue;
                }
                input.add(toolItem);
            }
        } else if (msg.has("function_call") && msg.get("function_call").isObject()) {
            JsonNode fc = msg.get("function_call");
            if (isBlankText(fc.get("name"))) {
                log.debug("Chat→Responses request: legacy function_call missing name, ignored");
                return;
            }
            ObjectNode funcItem = JSON.createObjectNode();
            funcItem.put("type", "function_call");
            String name = fc.get("name").asText();
            funcItem.put("call_id", name);
            funcItem.put("name", name);
            String args = textOrDefault(fc.get("arguments"), "{}");
            funcItem.put("arguments", args.isEmpty() ? "{}" : args);
            input.add(funcItem);
        }
    }

    private static ObjectNode convertChatReasoningContentToResponsesReasoning(String reasoningContent) {
        ObjectNode reasoningItem = JSON.createObjectNode();
        reasoningItem.put("type", "reasoning");
        reasoningItem.put("status", "completed");

        ArrayNode content = JSON.createArrayNode();
        ObjectNode contentText = JSON.createObjectNode();
        contentText.put("type", "reasoning_text");
        contentText.put("text", reasoningContent);
        content.add(contentText);
        reasoningItem.set("content", content);

        ArrayNode summary = JSON.createArrayNode();
        ObjectNode summaryText = JSON.createObjectNode();
        summaryText.put("type", "summary_text");
        summaryText.put("text", reasoningContent);
        summary.add(summaryText);
        reasoningItem.set("summary", summary);

        return reasoningItem;
    }

    /**
     * Tool 消息（role="tool"）→ function_call_output item。
     */
    private static void convertChatToolMessage(JsonNode msg, ArrayNode input) {
        if (isBlankText(msg.get("tool_call_id"))) {
            log.debug("Chat→Responses request: tool message missing tool_call_id, ignored");
            return;
        }
        ObjectNode item = JSON.createObjectNode();
        item.put("type", "function_call_output");
        item.put("call_id", msg.get("tool_call_id").asText());
        String output = flattenContent(msg.get("content"));
        item.put("output", output);
        input.add(item);
    }

    /**
     * Function 消息（旧式 role="function"）→ function_call_output item。
     * 使用 name 字段作为 call_id（旧格式无独立 tool_call_id）。
     */
    private static void convertChatFunctionMessage(JsonNode msg, ArrayNode input) {
        if (isBlankText(msg.get("name"))) {
            log.debug("Chat→Responses request: legacy function message missing name, ignored");
            return;
        }
        ObjectNode item = JSON.createObjectNode();
        item.put("type", "function_call_output");
        item.put("call_id", msg.get("name").asText());
        String output = flattenContent(msg.get("content"));
        item.put("output", output);
        input.add(item);
    }

    /**
     * 解析 assistant content。
     * <p>
     * 官方 Chat assistant content part 仅按文本/拒绝文本处理；thinking/reasoning 是兼容扩展，
     * 需要作为 Responses reasoning item 保留，不能伪造成 output_text。
     */
    private static ParsedAssistantContent parseAssistantContent(JsonNode content) {
        if (content == null) return new ParsedAssistantContent("", List.of());
        if (content.isNull()) return new ParsedAssistantContent("", List.of());
        if (content.isTextual()) return new ParsedAssistantContent(content.asText(), List.of());
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            List<String> reasoningTexts = new ArrayList<>();
            for (JsonNode part : content) {
                String partType = textOrDefault(part.get("type"), "");
                if ("thinking".equals(partType) || "reasoning".equals(partType)) {
                    String thinkingText = textOrDefault(part.get("thinking"), textOrDefault(part.get("text"), ""));
                    if (!thinkingText.isEmpty()) {
                        reasoningTexts.add(thinkingText);
                    }
                } else if ("refusal".equals(partType) && !isBlankText(part.get("refusal"))) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(part.get("refusal").asText());
                } else if (!isBlankText(part.get("text"))) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(part.get("text").asText());
                }
            }
            return new ParsedAssistantContent(sb.toString(), reasoningTexts);
        }
        return new ParsedAssistantContent("", List.of());
    }

    private record ParsedAssistantContent(String text, List<String> reasoningTexts) {}

    /**
     * 将 content 拍平为纯文本（支持字符串和数组格式）。
     */
    private static String flattenContent(JsonNode content) {
        if (content == null) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (!isBlankText(part.get("text"))) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(part.get("text").asText());
                } else if (part.isTextual()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(part.asText());
                }
            }
            return sb.toString();
        }
        return "";
    }

    // ========================
    // 映射方法
    // ========================

    /**
     * Chat finish_reason → Responses status。
     * stop→"completed", length/content_filter→"incomplete", tool_calls→"completed"
     */
    private static String mapChatFinishReasonToResponsesStatus(String finishReason) {
        return isIncompleteFinishReason(finishReason) ? "incomplete" : "completed";
    }

    private static boolean isIncompleteFinishReason(String finishReason) {
        return "length".equals(finishReason) || "content_filter".equals(finishReason);
    }

    private static String mapChatFinishReasonToResponsesIncompleteReason(String finishReason) {
        return "content_filter".equals(finishReason) ? "content_filter" : "max_output_tokens";
    }

    /**
     * 旧式 function_call → tool_choice。
     * "auto"→"auto", "none"→"none", {"name":"X"}→{"type":"function","name":"X"}
     */
    private static JsonNode convertLegacyFunctionCall(JsonNode functionCall) {
        if (functionCall.isTextual()) {
            return isSupportedLegacyFunctionCallMode(functionCall.asText()) ? functionCall : null;
        }
        if (functionCall.isObject() && functionCall.has("name")) {
            if (isBlankText(functionCall.get("name"))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put("type", "function");
            obj.put("name", functionCall.get("name").asText());
            return obj;
        }
        return null;
    }

    /**
     * Chat tool_choice object → Responses tool_choice object.
     * Chat: {"type":"function","function":{"name":"x"}}
     * Responses: {"type":"function","name":"x"}
     */
    private static JsonNode convertChatToolChoiceToResponses(JsonNode toolChoice) {
        if (toolChoice == null || toolChoice.isNull()) {
            return null;
        }
        if (toolChoice.isTextual()) {
            return isSupportedToolChoiceMode(toolChoice.asText()) ? toolChoice : null;
        }
        if (toolChoice.isObject()
                && "function".equals(toolChoice.path("type").asText(""))
                && toolChoice.has("function")
                && toolChoice.get("function").isObject()) {
            if (isBlankText(toolChoice.get("function").get("name"))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put("type", "function");
            obj.put("name", toolChoice.get("function").get("name").asText());
            return obj;
        }
        if (toolChoice.isObject()
                && "custom".equals(toolChoice.path("type").asText(""))
                && toolChoice.has("custom")
                && toolChoice.get("custom").isObject()) {
            if (isBlankText(toolChoice.get("custom").get("name"))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put("type", "custom");
            obj.put("name", toolChoice.get("custom").get("name").asText());
            return obj;
        }
        if (toolChoice.isObject()
                && "allowed_tools".equals(toolChoice.path("type").asText(""))
                && toolChoice.has("allowed_tools")
                && toolChoice.get("allowed_tools").isObject()) {
            ObjectNode obj = JSON.createObjectNode();
            obj.put("type", "allowed_tools");
            JsonNode allowed = toolChoice.get("allowed_tools");
            String mode = normalizeAllowedToolsMode(allowed.get("mode"));
            if (mode != null) {
                obj.put("mode", mode);
            }
            if (allowed.has("tools") && allowed.get("tools").isArray()) {
                ArrayNode tools = JSON.createArrayNode();
                for (JsonNode tool : allowed.get("tools")) {
                    JsonNode converted = convertChatAllowedToolToResponses(tool);
                    if (converted != null) tools.add(converted);
                }
                if (tools.size() == 0) return null;
                obj.set("tools", tools);
            }
            return obj;
        }
        return null;
    }

    private static boolean isSupportedToolChoiceMode(String mode) {
        return "auto".equals(mode) || "none".equals(mode) || "required".equals(mode);
    }

    private static boolean isSupportedLegacyFunctionCallMode(String mode) {
        return "auto".equals(mode) || "none".equals(mode);
    }

    private static JsonNode convertChatAllowedToolToResponses(JsonNode tool) {
        if (tool == null || !tool.isObject()) return null;
        String type = tool.path("type").asText("");
        if ("function".equals(type) && tool.has("function") && tool.get("function").isObject()) {
            if (isBlankText(tool.get("function").get("name"))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put("type", "function");
            obj.put("name", tool.get("function").path("name").asText(""));
            return obj;
        }
        if ("custom".equals(type) && tool.has("custom") && tool.get("custom").isObject()) {
            if (isBlankText(tool.get("custom").get("name"))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put("type", "custom");
            obj.put("name", tool.get("custom").path("name").asText(""));
            return obj;
        }
        return null;
    }

    private static JsonNode convertChatCustomToolFormatToResponses(JsonNode format) {
        if (format == null || !format.isObject() || isBlankText(format.get("type"))) return null;
        String type = format.get("type").asText();
        ObjectNode normalized = JSON.createObjectNode();
        if ("text".equals(type)) {
            normalized.put("type", "text");
            return normalized;
        }
        if ("grammar".equals(type)) {
            JsonNode grammar = format.get("grammar");
            if (grammar == null || !grammar.isObject()) return null;
            String syntax = normalizeCustomToolGrammarSyntax(grammar.get("syntax"));
            if (syntax == null || isBlankText(grammar.get("definition"))) return null;
            normalized.put("type", "grammar");
            normalized.put("syntax", syntax);
            normalized.put("definition", grammar.get("definition").asText());
            return normalized;
        }
        return null;
    }

    private static String normalizeCustomToolGrammarSyntax(JsonNode syntax) {
        if (syntax == null || !syntax.isTextual()) return null;
        return switch (syntax.asText()) {
            case "lark", "regex" -> syntax.asText();
            default -> null;
        };
    }

    /**
     * 检测 base64 data URI 是否为空载荷。
     */
    private static boolean isEmptyBase64DataURI(String url) {
        if (url == null || !url.startsWith("data:")) return false;
        int commaIdx = url.indexOf(',');
        if (commaIdx < 0) return false;
        return url.substring(commaIdx + 1).trim().isEmpty();
    }

    private static boolean hasChatFilePayload(JsonNode file) {
        return !isBlankText(file.get("file_data")) || !isBlankText(file.get("file_id"));
    }

    private static boolean hasChatAudioPayload(JsonNode inputAudio) {
        return !isBlankText(inputAudio.get("data")) && !isBlankText(inputAudio.get("format"));
    }

    private static JsonNode normalizeStopSequences(JsonNode stop) {
        if (stop == null || stop.isNull()) return null;
        ArrayNode arr = JSON.createArrayNode();
        if (stop.isArray()) {
            for (JsonNode item : stop) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    arr.add(item.asText());
                }
            }
            return arr.isEmpty() ? null : arr;
        }
        if (stop.isTextual()) {
            String text = stop.asText();
            if (text.isBlank()) {
                return null;
            }
            arr.add(text);
            return arr;
        }
        return null;
    }

    private static boolean isPositiveInt(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.asInt() > 0;
    }

    private static boolean isNonNegativeInt(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.asInt() >= 0;
    }

    private static boolean isNonNegativeLong(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong() && node.asLong() >= 0;
    }

    private static int nonNegativeIntOrZero(JsonNode node) {
        return isNonNegativeInt(node) ? node.asInt() : 0;
    }

    private static int nonNegativeIntOrDefault(JsonNode node, int defaultValue) {
        return isNonNegativeInt(node) ? node.asInt() : defaultValue;
    }

    private static boolean isValidTopLogprobs(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt()
                && node.asInt() >= 0 && node.asInt() <= 20;
    }

    private static String normalizeChatReasoningEffort(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        return switch (node.asText()) {
            case "minimal", "low", "medium", "high", "xhigh" -> node.asText();
            default -> null;
        };
    }

    private static String normalizeVerbosity(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        return switch (node.asText()) {
            case "low", "medium", "high" -> node.asText();
            default -> null;
        };
    }

    private static JsonNode normalizeToolParameters(JsonNode parameters) {
        if (parameters != null && parameters.isObject()) {
            return parameters;
        }
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", JSON.createObjectNode());
        return schema;
    }

    private static boolean isBlankText(JsonNode node) {
        return node == null || !node.isTextual() || node.asText().isBlank();
    }

    private static String textOrDefault(JsonNode node, String defaultValue) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : defaultValue;
    }

    private static JsonNode convertChatResponseFormatToResponses(JsonNode responseFormat) {
        String type = responseFormat.path("type").asText("");
        if ("json_schema".equals(type)) {
            JsonNode jsonSchema = responseFormat.path("json_schema");
            if (!jsonSchema.isObject()) return null;
            if (isBlankText(jsonSchema.get("name"))) return null;
            if (!jsonSchema.has("schema") || jsonSchema.get("schema").isNull()) return null;
            ObjectNode format = JSON.createObjectNode();
            format.put("type", "json_schema");
            format.set("name", jsonSchema.get("name"));
            copyTextIfExists(jsonSchema, format, "description");
            format.set("schema", jsonSchema.get("schema"));
            if (jsonSchema.has("strict") && jsonSchema.get("strict").isBoolean()) {
                format.set("strict", jsonSchema.get("strict"));
            }
            return format;
        }
        if ("json_object".equals(type) || "text".equals(type)) {
            ObjectNode format = JSON.createObjectNode();
            format.put("type", type);
            return format;
        }
        return null;
    }

    private static ObjectNode convertChatWebSearchOptionsToResponsesTool(JsonNode webSearchOptions) {
        if (webSearchOptions == null || !webSearchOptions.isObject()) return null;
        ObjectNode tool = JSON.createObjectNode();
        tool.put("type", "web_search_preview");
        String searchContextSize = normalizeSearchContextSize(webSearchOptions.get("search_context_size"));
        if (searchContextSize != null) {
            tool.put("search_context_size", searchContextSize);
        }

        JsonNode userLocation = webSearchOptions.get("user_location");
        if (userLocation != null && userLocation.isObject()) {
            JsonNode approximate = userLocation.has("approximate")
                    ? userLocation.get("approximate")
                    : userLocation;
            if (approximate != null && approximate.isObject()) {
                ObjectNode location = JSON.createObjectNode();
                location.put("type", "approximate");
                copyTextIfExists(approximate, location, "country");
                copyTextIfExists(approximate, location, "region");
                copyTextIfExists(approximate, location, "city");
                copyTextIfExists(approximate, location, "timezone");
                if (location.size() > 1) {
                    tool.set("user_location", location);
                }
            }
        }
        return tool;
    }

    // ========================
    // 通用工具方法
    // ========================

    private static void copyIfExists(JsonNode src, ObjectNode dst, String field) {
        if (src.has(field) && !src.get(field).isNull()) dst.set(field, src.get(field));
    }

    private static void copyObjectIfExists(JsonNode src, ObjectNode dst, String field) {
        if (src.has(field) && src.get(field).isObject()) dst.set(field, src.get(field));
    }

    private static void copyTextIfExists(JsonNode src, ObjectNode dst, String field) {
        if (src.has(field) && src.get(field).isTextual() && !src.get(field).asText().isBlank()) {
            dst.set(field, src.get(field));
        }
    }

    private static void copyBooleanIfExists(JsonNode src, ObjectNode dst, String field) {
        if (src.has(field) && src.get(field).isBoolean()) dst.set(field, src.get(field));
    }

    private static void copyNumberIfExists(JsonNode src, ObjectNode dst, String field) {
        if (src.has(field) && src.get(field).isNumber()) dst.set(field, src.get(field));
    }

    private static String normalizeSearchContextSize(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        return switch (node.asText()) {
            case "low", "medium", "high" -> node.asText();
            default -> null;
        };
    }

    private static String normalizeAllowedToolsMode(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        return isSupportedToolChoiceMode(node.asText()) ? node.asText() : null;
    }

    private static String normalizeImageDetail(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        return switch (node.asText()) {
            case "auto", "low", "high" -> node.asText();
            default -> null;
        };
    }

    private static void appendEvent(List<String> output, String event, String data) {
        output.add("event: " + event);
        output.add("data: " + data);
        output.add("");
    }

    private static String escapeJsonValue(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String fmt(String format, Object... args) {
        return String.format(format, args);
    }
}
