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
            copyIfExists(src, dst, "model");
            if (src.has("instructions")) dst.put("instructions", src.get("instructions").asText());
            if (src.has("service_tier")) dst.put("service_tier", src.get("service_tier").asText());
            copyIfExists(src, dst, "metadata");
            copyIfExists(src, dst, "parallel_tool_calls");
            copyIfExists(src, dst, "user");

            // 协议转换层不根据模型名猜能力；采样参数按客户端显式请求保留。
            copyIfExists(src, dst, "temperature");
            copyIfExists(src, dst, "top_p");

            // response_format → text.format
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

            // max_tokens / max_completion_tokens → max_output_tokens（后者优先）
            int maxTokens = 0;
            if (src.has("max_completion_tokens")) {
                maxTokens = src.get("max_completion_tokens").asInt();
            } else if (src.has("max_tokens")) {
                maxTokens = src.get("max_tokens").asInt();
            }
            if (maxTokens > 0) {
                dst.put("max_output_tokens", maxTokens);
            }

            // stream：保留客户端语义；未传时让 Responses 使用官方默认的非流式。
            copyIfExists(src, dst, "stream");

            // stop → 内部 IR 扩展。Responses 上游不直接消费，跨协议转 Anthropic/Chat 时再还原。
            if (src.has("stop") && !src.get("stop").isNull()) {
                JsonNode stop = normalizeStopSequences(src.get("stop"));
                if (stop != null) dst.set("_landgate_stop_sequences", stop);
            }

            // reasoning_effort → reasoning
            if (src.has("reasoning_effort")) {
                ObjectNode reasoning = JSON.createObjectNode();
                reasoning.put("effort", src.get("reasoning_effort").asText());
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
                    if ("function".equals(tool.has("type") ? tool.get("type").asText() : "")
                            && tool.has("function")) {
                        JsonNode func = tool.get("function");
                        ObjectNode rt = JSON.createObjectNode();
                        rt.put("type", "function");
                        rt.put("name", func.has("name") ? func.get("name").asText() : "");
                        if (func.has("description")) rt.put("description", func.get("description").asText());
                        if (func.has("parameters")) rt.set("parameters", func.get("parameters"));
                        rt.put("strict", func.has("strict") && func.get("strict").asBoolean(false));
                        responsesTools.add(rt);
                    }
                }
            }
            // 旧式 functions[] → tools
            if (src.has("functions") && src.get("functions").isArray()) {
                for (JsonNode func : src.get("functions")) {
                    ObjectNode rt = JSON.createObjectNode();
                    rt.put("type", "function");
                    rt.put("name", func.has("name") ? func.get("name").asText() : "");
                    if (func.has("description")) rt.put("description", func.get("description").asText());
                    if (func.has("parameters")) rt.set("parameters", func.get("parameters"));
                    rt.put("strict", false);
                    responsesTools.add(rt);
                }
            }
            if (responsesTools.size() > 0) {
                dst.set("tools", responsesTools);
            }

            // --- tool_choice ---
            if (src.has("tool_choice") && !src.get("tool_choice").isNull()) {
                dst.set("tool_choice", convertChatToolChoiceToResponses(src.get("tool_choice")));
            } else if (src.has("function_call") && !src.get("function_call").isNull()) {
                // 旧式 function_call → tool_choice
                dst.set("tool_choice", convertLegacyFunctionCall(src.get("function_call")));
            }

            // --- 固定字段 ---
            dst.put("store", false);
            ArrayNode include = JSON.createArrayNode();
            include.add("reasoning.encrypted_content");
            dst.set("include", include);

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

            dst.put("id", src.has("id") ? src.get("id").asText()
                    : "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            dst.put("object", "response");
            dst.put("model", src.has("model") ? src.get("model").asText() : "unknown");

            if (!src.has("choices") || src.get("choices").size() == 0) {
                // 无 choices → 空响应
                dst.put("status", "completed");
                dst.set("output", JSON.createArrayNode());
                return dst;
            }

            JsonNode choice = src.get("choices").get(0);
            JsonNode message = choice.has("message") ? choice.get("message") : null;
            String finishReason = choice.has("finish_reason") ? choice.get("finish_reason").asText() : "stop";

            ArrayNode output = JSON.createArrayNode();
            boolean hasToolCalls = false;

            if (message != null) {
                // reasoning_content → reasoning output item
                if (message.has("reasoning_content") && !message.get("reasoning_content").isNull()) {
                    ObjectNode reasoningItem = JSON.createObjectNode();
                    reasoningItem.put("type", "reasoning");
                    reasoningItem.put("status", "completed");
                    ArrayNode summary = JSON.createArrayNode();
                    ObjectNode summaryText = JSON.createObjectNode();
                    summaryText.put("type", "summary_text");
                    summaryText.put("text", message.get("reasoning_content").asText());
                    summary.add(summaryText);
                    reasoningItem.set("summary", summary);
                    output.add(reasoningItem);
                }

                // content/refusal → output_text/refusal
                String contentText = message.has("content") && !message.get("content").isNull()
                        ? message.get("content").asText() : "";
                String refusalText = message.has("refusal") && !message.get("refusal").isNull()
                        ? message.get("refusal").asText() : "";

                // tool_calls / legacy function_call → function_call output items
                ArrayNode toolCalls = null;
                if (message.has("tool_calls") && message.get("tool_calls").isArray()) {
                    toolCalls = (ArrayNode) message.get("tool_calls");
                    hasToolCalls = true;
                    for (JsonNode tc : toolCalls) {
                        ObjectNode funcItem = JSON.createObjectNode();
                        funcItem.put("type", "function_call");
                        funcItem.put("call_id", tc.has("id") ? tc.get("id").asText() : "");
                        funcItem.put("status", "completed");
                        if (tc.has("function")) {
                            JsonNode func = tc.get("function");
                            funcItem.put("name", func.has("name") ? func.get("name").asText() : "");
                            funcItem.put("arguments", func.has("arguments") ? func.get("arguments").asText() : "{}");
                        }
                        output.add(funcItem);
                    }
                } else if (message.has("function_call") && message.get("function_call").isObject()) {
                    JsonNode fc = message.get("function_call");
                    ObjectNode funcItem = JSON.createObjectNode();
                    funcItem.put("type", "function_call");
                    String name = fc.has("name") ? fc.get("name").asText() : "";
                    funcItem.put("call_id", name.isEmpty() ? "function_call" : name);
                    funcItem.put("status", "completed");
                    funcItem.put("name", name);
                    funcItem.put("arguments", fc.has("arguments") ? fc.get("arguments").asText() : "{}");
                    output.add(funcItem);
                    hasToolCalls = true;
                }

                // message item（包含文本内容）
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

            dst.set("output", output);

            // finish_reason → status
            String status = mapChatFinishReasonToResponsesStatus(finishReason);
            dst.put("status", status);
            if ("incomplete".equals(status)) {
                ObjectNode incompleteDetails = JSON.createObjectNode();
                incompleteDetails.put("reason", "max_output_tokens");
                dst.set("incomplete_details", incompleteDetails);
            }

            // usage
            if (src.has("usage")) {
                JsonNode usage = src.get("usage");
                ObjectNode respUsage = JSON.createObjectNode();
                int inputTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : 0;
                int outputTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : 0;
                respUsage.put("input_tokens", inputTokens);
                respUsage.put("output_tokens", outputTokens);
                respUsage.put("total_tokens", usage.has("total_tokens")
                        ? usage.get("total_tokens").asInt() : inputTokens + outputTokens);
                if (usage.has("prompt_tokens_details")
                        && usage.get("prompt_tokens_details").has("cached_tokens")
                        && usage.get("prompt_tokens_details").get("cached_tokens").asInt() > 0) {
                    ObjectNode details = JSON.createObjectNode();
                    details.put("cached_tokens", usage.get("prompt_tokens_details").get("cached_tokens").asInt());
                    respUsage.set("input_tokens_details", details);
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
        private final long createdAt;
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
                    String text = delta.get("content").asText();
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
                    String refusal = delta.get("refusal").asText();
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
                    String rc = delta.get("reasoning_content").asText();
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
                        int tcIndex = tc.has("index") ? tc.get("index").asInt() : nextToolCallIndex;

                        // 新增 tool_call
                        if (tc.has("id") && tc.has("function") && tc.get("function").has("name")) {
                            String callId = tc.get("id").asText();
                            String funcName = tc.get("function").get("name").asText();

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
                            String args = tc.get("function").get("arguments").asText();
                            if (!args.isEmpty()) {
                                String callId = pendingToolCallIds.size() > tcIndex && pendingToolCallIds.get(tcIndex) != null
                                        ? pendingToolCallIds.get(tcIndex)
                                        : "call_" + tcIndex;
                                String funcName = pendingToolCallNames.size() > tcIndex && pendingToolCallNames.get(tcIndex) != null
                                        ? pendingToolCallNames.get(tcIndex)
                                        : "";
                                ToolCallState state = ensureToolCallState(output, tcIndex, callId, funcName);
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
                    String funcName = fc.has("name") ? fc.get("name").asText() : "";
                    String callId = funcName.isEmpty() ? "function_call" : funcName;
                    ToolCallState state = ensureToolCallState(output, tcIndex, callId, funcName);
                    hasToolCalls = true;
                    if (fc.has("arguments")) {
                        String args = fc.get("arguments").asText();
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
            String status = "length".equals(finishReason) ? "incomplete" : "completed";
            String incompleteDetails = "length".equals(finishReason)
                    ? ",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}"
                    : "";
            String cacheDetails = cachedTokens > 0
                    ? fmt(",\"input_tokens_details\":{\"cached_tokens\":%d}", cachedTokens)
                    : "";
            appendEvent(output, "response.completed",
                    fmt("{\"type\":\"response.completed\",\"sequence_number\":%d,\"response\":{\"id\":\"%s\",\"object\":\"response\",\"model\":\"%s\",\"created_at\":%d,\"status\":\"%s\"%s,\"usage\":{\"input_tokens\":%d,\"output_tokens\":%d,\"total_tokens\":%d%s}}}",
                            sequenceNumber++, responseId, escapeJsonValue(model), createdAt, status, incompleteDetails,
                            inputTokens, outputTokens, inputTokens + outputTokens, cacheDetails));
        }

        private void extractUsage(JsonNode usage) {
            if (usage.has("prompt_tokens")) inputTokens = usage.get("prompt_tokens").asInt();
            if (usage.has("completion_tokens")) outputTokens = usage.get("completion_tokens").asInt();
            if (usage.has("prompt_tokens_details")
                    && usage.get("prompt_tokens_details").has("cached_tokens")) {
                cachedTokens = usage.get("prompt_tokens_details").get("cached_tokens").asInt();
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
                if ("text".equals(part.has("type") ? part.get("type").asText() : "")
                        && part.has("text")) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(part.get("text").asText());
                }
            }
            item.put("content", sb.toString());
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
                String partType = part.has("type") ? part.get("type").asText() : "";
                switch (partType) {
                    case "text" -> {
                        ObjectNode p = JSON.createObjectNode();
                        p.put("type", "input_text");
                        p.put("text", part.has("text") ? part.get("text").asText() : "");
                        parts.add(p);
                    }
                    case "image_url" -> {
                        if (part.has("image_url") && part.get("image_url").has("url")) {
                            String url = part.get("image_url").get("url").asText();
                            if (!isEmptyBase64DataURI(url)) {
                                ObjectNode p = JSON.createObjectNode();
                                p.put("type", "input_image");
                                p.put("image_url", url);
                                parts.add(p);
                            }
                        }
                    }
                    default ->
                        log.debug("Chat→Responses: unknown content part type '{}'", partType);
                }
            }
            item.set("content", parts);
        }
        input.add(item);
    }

    /**
     * Assistant 消息 → assistant message item + function_call items。
     */
    private static void convertChatAssistantMessage(JsonNode msg, ArrayNode input) {
        // 解析 content
        String contentText = parseAssistantContent(msg.get("content"));
        // reasoning_content
        String reasoningContent = msg.has("reasoning_content") ? msg.get("reasoning_content").asText() : null;

        // 构建 content 文本（reasoning 包裹在 <thinking> 标签中）
        StringBuilder fullContent = new StringBuilder();
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            fullContent.append("<thinking>").append(reasoningContent).append("</thinking>");
        }
        if (contentText != null && !contentText.isEmpty()) {
            if (fullContent.length() > 0) fullContent.append("\n");
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

        // tool_calls → function_call items
        if (msg.has("tool_calls") && msg.get("tool_calls").isArray()) {
            for (JsonNode tc : msg.get("tool_calls")) {
                ObjectNode funcItem = JSON.createObjectNode();
                funcItem.put("type", "function_call");
                funcItem.put("call_id", tc.has("id") ? tc.get("id").asText() : "");
                if (tc.has("function")) {
                    JsonNode func = tc.get("function");
                    funcItem.put("name", func.has("name") ? func.get("name").asText() : "");
                    String args = func.has("arguments") ? func.get("arguments").asText() : "{}";
                    funcItem.put("arguments", args.isEmpty() ? "{}" : args);
                }
                input.add(funcItem);
            }
        } else if (msg.has("function_call") && msg.get("function_call").isObject()) {
            JsonNode fc = msg.get("function_call");
            ObjectNode funcItem = JSON.createObjectNode();
            funcItem.put("type", "function_call");
            String name = fc.has("name") ? fc.get("name").asText() : "";
            funcItem.put("call_id", name.isEmpty() ? "function_call" : name);
            funcItem.put("name", name);
            String args = fc.has("arguments") ? fc.get("arguments").asText() : "{}";
            funcItem.put("arguments", args.isEmpty() ? "{}" : args);
            input.add(funcItem);
        }
    }

    /**
     * Tool 消息（role="tool"）→ function_call_output item。
     */
    private static void convertChatToolMessage(JsonNode msg, ArrayNode input) {
        ObjectNode item = JSON.createObjectNode();
        item.put("type", "function_call_output");
        item.put("call_id", msg.has("tool_call_id") ? msg.get("tool_call_id").asText() : "");
        String output = flattenContent(msg.get("content"));
        item.put("output", output.isEmpty() ? "(empty)" : output);
        input.add(item);
    }

    /**
     * Function 消息（旧式 role="function"）→ function_call_output item。
     * 使用 name 字段作为 call_id（旧格式无独立 tool_call_id）。
     */
    private static void convertChatFunctionMessage(JsonNode msg, ArrayNode input) {
        ObjectNode item = JSON.createObjectNode();
        item.put("type", "function_call_output");
        item.put("call_id", msg.has("name") ? msg.get("name").asText() : "");
        String output = flattenContent(msg.get("content"));
        item.put("output", output.isEmpty() ? "(empty)" : output);
        input.add(item);
    }

    /**
     * 解析 assistant content —— 支持字符串、数组（含 thinking/reasoning 块包裹在 <thinking> 标签中）。
     */
    private static String parseAssistantContent(JsonNode content) {
        if (content == null) return "";
        if (content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String partType = part.has("type") ? part.get("type").asText() : "";
                if ("thinking".equals(partType) || "reasoning".equals(partType)) {
                    String thinkingText = part.has("thinking") ? part.get("thinking").asText()
                            : part.has("text") ? part.get("text").asText() : "";
                    if (!thinkingText.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append("<thinking>").append(thinkingText).append("</thinking>");
                    }
                } else if (part.has("text")) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(part.get("text").asText());
                }
            }
            return sb.toString();
        }
        return content.asText();
    }

    /**
     * 将 content 拍平为纯文本（支持字符串和数组格式）。
     */
    private static String flattenContent(JsonNode content) {
        if (content == null) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.has("text")) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(part.get("text").asText());
                } else if (part.isTextual()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(part.asText());
                }
            }
            return sb.toString();
        }
        return content.asText();
    }

    // ========================
    // 映射方法
    // ========================

    /**
     * Chat finish_reason → Responses status。
     * stop→"completed", length→"incomplete", tool_calls→"completed", content_filter→"completed"
     */
    private static String mapChatFinishReasonToResponsesStatus(String finishReason) {
        return switch (finishReason) {
            case "length" -> "incomplete";
            default -> "completed";
        };
    }

    /**
     * 旧式 function_call → tool_choice。
     * "auto"→"auto", "none"→"none", {"name":"X"}→{"type":"function","name":"X"}
     */
    private static JsonNode convertLegacyFunctionCall(JsonNode functionCall) {
        if (functionCall.isTextual()) {
            return functionCall; // "auto" / "none" 透传
        }
        if (functionCall.isObject() && functionCall.has("name")) {
            ObjectNode obj = JSON.createObjectNode();
            obj.put("type", "function");
            obj.put("name", functionCall.get("name").asText());
            return obj;
        }
        return functionCall;
    }

    /**
     * Chat tool_choice object → Responses tool_choice object.
     * Chat: {"type":"function","function":{"name":"x"}}
     * Responses: {"type":"function","name":"x"}
     */
    private static JsonNode convertChatToolChoiceToResponses(JsonNode toolChoice) {
        if (toolChoice == null || toolChoice.isNull() || toolChoice.isTextual()) {
            return toolChoice;
        }
        if (toolChoice.isObject()
                && "function".equals(toolChoice.path("type").asText(""))
                && toolChoice.has("function")
                && toolChoice.get("function").isObject()) {
            ObjectNode obj = JSON.createObjectNode();
            obj.put("type", "function");
            obj.put("name", toolChoice.get("function").path("name").asText(""));
            return obj;
        }
        return toolChoice;
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

    private static JsonNode normalizeStopSequences(JsonNode stop) {
        if (stop == null || stop.isNull()) return null;
        if (stop.isArray()) return stop;
        if (stop.isTextual()) {
            ArrayNode arr = JSON.createArrayNode();
            arr.add(stop.asText());
            return arr;
        }
        return null;
    }

    private static JsonNode convertChatResponseFormatToResponses(JsonNode responseFormat) {
        String type = responseFormat.path("type").asText("");
        if ("json_schema".equals(type)) {
            JsonNode jsonSchema = responseFormat.path("json_schema");
            if (!jsonSchema.isObject()) return null;
            ObjectNode format = JSON.createObjectNode();
            format.put("type", "json_schema");
            if (jsonSchema.has("name")) format.set("name", jsonSchema.get("name"));
            if (jsonSchema.has("description")) format.set("description", jsonSchema.get("description"));
            if (jsonSchema.has("schema")) format.set("schema", jsonSchema.get("schema"));
            if (jsonSchema.has("strict")) format.set("strict", jsonSchema.get("strict"));
            return format;
        }
        if ("json_object".equals(type) || "text".equals(type)) {
            ObjectNode format = JSON.createObjectNode();
            format.put("type", type);
            return format;
        }
        return null;
    }

    // ========================
    // 通用工具方法
    // ========================

    private static void copyIfExists(JsonNode src, ObjectNode dst, String field) {
        if (src.has(field) && !src.get(field).isNull()) dst.set(field, src.get(field));
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
