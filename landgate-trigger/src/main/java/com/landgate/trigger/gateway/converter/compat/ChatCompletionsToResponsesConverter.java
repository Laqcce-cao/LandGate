package com.landgate.trigger.gateway.converter.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.trigger.gateway.converter.StreamTranslator;
import com.landgate.types.gateway.GatewayProtocolIrPolicy;
import com.landgate.types.gateway.OpenAiChatCompletionsBodyPolicy;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;
import com.landgate.types.gateway.OpenAiResponsesJsonPolicy;
import com.landgate.types.gateway.OpenAiResponsesSsePolicy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.landgate.types.gateway.OpenAiChatCompletionsBodyPolicy.*;

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
            if (isResponsesShapeChatBody(src)) {
                return responsesShapeChatBodyToIR((ObjectNode) src);
            }
            ObjectNode dst = JSON.createObjectNode();

            // --- 基础字段 ---
            copyTextIfExists(src, dst, FIELD_MODEL);
            copyTextIfExists(src, dst, OpenAiChatCompletionsBodyPolicy.FIELD_INSTRUCTIONS);
            copyNormalizedServiceTierIfExists(src, dst);

            // 协议转换层不根据模型名猜能力；采样参数按客户端显式请求保留。
            copyNumberIfExists(src, dst, OpenAiResponsesBodyPolicy.FIELD_TEMPERATURE);
            copyNumberIfExists(src, dst, OpenAiResponsesBodyPolicy.FIELD_TOP_P);

            // max_tokens / max_completion_tokens → max_output_tokens（后者优先）
            JsonNode maxTokensNode = null;
            if (src.has(OpenAiResponsesBodyPolicy.FIELD_MAX_COMPLETION_TOKENS)) {
                maxTokensNode = src.get(OpenAiResponsesBodyPolicy.FIELD_MAX_COMPLETION_TOKENS);
            } else if (src.has(FIELD_MAX_TOKENS)) {
                maxTokensNode = src.get(FIELD_MAX_TOKENS);
            }
            if (isPositiveInt(maxTokensNode)) {
                dst.put(OpenAiResponsesBodyPolicy.FIELD_MAX_OUTPUT_TOKENS,
                        Math.max(maxTokensNode.asInt(), OpenAiResponsesBodyPolicy.MIN_MAX_OUTPUT_TOKENS));
            }

            // 对齐 sub2api：Responses/Codex 上游固定使用流式，客户端非流式由网关聚合。
            dst.put(FIELD_STREAM, true);

            // stop → 内部 IR 扩展。Responses 上游不直接消费，跨协议转 Anthropic/Chat 时再还原。
            if (src.has(FIELD_STOP) && !src.get(FIELD_STOP).isNull()) {
                JsonNode stop = normalizeStopSequences(src.get(FIELD_STOP));
                if (stop != null) dst.set(GatewayProtocolIrPolicy.FIELD_STOP_SEQUENCES, stop);
            }

            // reasoning.effort / reasoning_effort → reasoning
            String reasoningEffort = extractChatReasoningEffort(src);
            if (reasoningEffort != null) {
                ObjectNode reasoning = JSON.createObjectNode();
                reasoning.put(OpenAiResponsesBodyPolicy.FIELD_EFFORT, reasoningEffort);
                reasoning.put(OpenAiResponsesBodyPolicy.FIELD_SUMMARY, OpenAiResponsesBodyPolicy.REASONING_SUMMARY_AUTO);
                dst.set(OpenAiResponsesBodyPolicy.FIELD_REASONING, reasoning);
            }

            // --- messages → input ---
            ArrayNode input = JSON.createArrayNode();
            if (src.has(FIELD_MESSAGES) && src.get(FIELD_MESSAGES).isArray()) {
                for (JsonNode msg : src.get(FIELD_MESSAGES)) {
                    String role = msg.has(FIELD_ROLE) ? msg.get(FIELD_ROLE).asText() : ROLE_USER;
                    JsonNode contentNode = msg.get(FIELD_CONTENT);

                    switch (role) {
                        case ROLE_SYSTEM -> convertChatInstructionMessage(ROLE_SYSTEM, contentNode, input);
                        case ROLE_USER -> convertChatUserMessage(contentNode, input);
                        case ROLE_ASSISTANT -> convertChatAssistantMessage(msg, input);
                        case ROLE_TOOL -> convertChatToolMessage(msg, input);
                        case ROLE_FUNCTION -> convertChatFunctionMessage(msg, input);
                        default -> convertChatUserMessage(contentNode, input);
                    }
                }
            }
            dst.set(OpenAiResponsesBodyPolicy.FIELD_INPUT, input);

            // --- tools ---
            ArrayNode responsesTools = JSON.createArrayNode();
            if (src.has(FIELD_TOOLS) && src.get(FIELD_TOOLS).isArray()) {
                for (JsonNode tool : src.get(FIELD_TOOLS)) {
                    String type = tool.has(FIELD_TYPE) ? tool.get(FIELD_TYPE).asText() : "";
                    if (TYPE_FUNCTION.equals(type) && tool.has(FIELD_FUNCTION)) {
                        JsonNode func = tool.get(FIELD_FUNCTION);
                        if (isBlankText(func.get(FIELD_NAME))) {
                            continue;
                        }
                        ObjectNode rt = JSON.createObjectNode();
                        rt.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, TYPE_FUNCTION);
                        rt.put(OpenAiResponsesJsonPolicy.FIELD_NAME, func.get(FIELD_NAME).asText());
                        copyTextIfExists(func, rt, FIELD_DESCRIPTION);
                        if (func.has(FIELD_PARAMETERS)) {
                            rt.set(FIELD_PARAMETERS, normalizeToolParameters(func.get(FIELD_PARAMETERS)));
                        }
                        copyBooleanIfExists(func, rt, FIELD_STRICT);
                        responsesTools.add(rt);
                    }
                }
            }
            // 旧式 functions[] → tools
            if (src.has(FIELD_FUNCTIONS) && src.get(FIELD_FUNCTIONS).isArray()) {
                for (JsonNode func : src.get(FIELD_FUNCTIONS)) {
                    if (isBlankText(func.get(FIELD_NAME))) {
                        continue;
                    }
                    ObjectNode rt = JSON.createObjectNode();
                    rt.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, TYPE_FUNCTION);
                    rt.put(OpenAiResponsesJsonPolicy.FIELD_NAME, func.get(FIELD_NAME).asText());
                    copyTextIfExists(func, rt, FIELD_DESCRIPTION);
                    if (func.has(FIELD_PARAMETERS)) {
                        rt.set(FIELD_PARAMETERS, normalizeToolParameters(func.get(FIELD_PARAMETERS)));
                    }
                    copyBooleanIfExists(func, rt, FIELD_STRICT);
                    responsesTools.add(rt);
                }
            }
            if (responsesTools.size() > 0) {
                dst.set(FIELD_TOOLS, responsesTools);
            }

            // --- tool_choice ---
            if (src.has(FIELD_TOOL_CHOICE) && !src.get(FIELD_TOOL_CHOICE).isNull()) {
                dst.set(FIELD_TOOL_CHOICE, src.get(FIELD_TOOL_CHOICE));
            } else if (src.has(FIELD_FUNCTION_CALL) && !src.get(FIELD_FUNCTION_CALL).isNull()) {
                // 旧式 function_call → tool_choice
                JsonNode toolChoice = convertLegacyFunctionCall(src.get(FIELD_FUNCTION_CALL));
                if (toolChoice != null) {
                    dst.set(FIELD_TOOL_CHOICE, toolChoice);
                }
            }

            // --- 固定字段 ---
            dst.put(OpenAiResponsesBodyPolicy.FIELD_STORE, false);
            ArrayNode include = JSON.createArrayNode();
            include.add(OpenAiResponsesBodyPolicy.INCLUDE_REASONING_ENCRYPTED_CONTENT);
            dst.set(OpenAiResponsesBodyPolicy.FIELD_INCLUDE, include);

            return dst;
        } catch (Exception e) {
            log.warn("ChatCompletions→Responses requestToIR error: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    private static boolean isResponsesShapeChatBody(JsonNode src) {
        return src instanceof ObjectNode
                && !src.has(FIELD_MESSAGES)
                && src.has(OpenAiResponsesBodyPolicy.FIELD_INPUT);
    }

    private static ObjectNode responsesShapeChatBodyToIR(ObjectNode src) {
        ObjectNode dst = src.deepCopy();
        dst.remove(OpenAiResponsesBodyPolicy.chatEndpointResponsesShapeUnsupportedFields());
        normalizeResponsesShapeServiceTier(dst);
        dst.put(OpenAiResponsesBodyPolicy.FIELD_STREAM, true);
        return dst;
    }

    private static void normalizeResponsesShapeServiceTier(ObjectNode dst) {
        JsonNode value = dst.get(OpenAiResponsesBodyPolicy.FIELD_SERVICE_TIER);
        if (value == null || !value.isTextual()) {
            return;
        }
        String normalized = OpenAiResponsesBodyPolicy.normalizeServiceTier(value.asText());
        if (normalized.isBlank()) {
            dst.remove(OpenAiResponsesBodyPolicy.FIELD_SERVICE_TIER);
        } else {
            dst.put(OpenAiResponsesBodyPolicy.FIELD_SERVICE_TIER, normalized);
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

            dst.put(OpenAiResponsesJsonPolicy.FIELD_ID, textOrDefault(src.get(FIELD_ID),
                    OpenAiResponsesJsonPolicy.RESPONSE_ID_PREFIX + randomOpenAiResponsesIdSuffix()));
            dst.put(OpenAiResponsesJsonPolicy.FIELD_OBJECT, OpenAiResponsesJsonPolicy.OBJECT_RESPONSE);
            dst.put(OpenAiResponsesJsonPolicy.FIELD_MODEL,
                    isBlankText(src.get(FIELD_MODEL)) ? OpenAiResponsesJsonPolicy.DEFAULT_MODEL : src.get(FIELD_MODEL).asText());
            if (isNonNegativeLong(src.get(FIELD_CREATED))) {
                dst.put(OpenAiResponsesJsonPolicy.FIELD_CREATED_AT, src.get(FIELD_CREATED).asLong());
            }

            if (!src.has(FIELD_CHOICES) || src.get(FIELD_CHOICES).size() == 0) {
                // 无 choices → 空响应
                dst.put(OpenAiResponsesJsonPolicy.FIELD_STATUS, OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
                dst.set(OpenAiResponsesJsonPolicy.FIELD_OUTPUT, JSON.createArrayNode());
                return dst;
            }

            JsonNode choice = src.get(FIELD_CHOICES).get(0);
            JsonNode message = choice.has(FIELD_MESSAGE) ? choice.get(FIELD_MESSAGE) : null;
            String finishReason = textOrDefault(choice.get(FIELD_FINISH_REASON), FINISH_REASON_STOP);

            ArrayNode output = JSON.createArrayNode();
            boolean hasToolCalls = false;

            if (message != null) {
                // reasoning_content → reasoning output item
                String reasoningContent = textOrDefault(message.get(FIELD_REASONING_CONTENT), "");
                if (!reasoningContent.isEmpty()) {
                    ObjectNode reasoningItem = JSON.createObjectNode();
                    reasoningItem.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_REASONING);
                    reasoningItem.put(OpenAiResponsesJsonPolicy.FIELD_STATUS, OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
                    ArrayNode summary = JSON.createArrayNode();
                    ObjectNode summaryText = JSON.createObjectNode();
                    summaryText.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_SUMMARY_TEXT);
                    summaryText.put(OpenAiResponsesJsonPolicy.FIELD_TEXT, reasoningContent);
                    summary.add(summaryText);
                    reasoningItem.set(OpenAiResponsesJsonPolicy.FIELD_SUMMARY, summary);
                    output.add(reasoningItem);
                }

                // content/refusal → output_text/refusal
                String contentText = textOrDefault(message.get(FIELD_CONTENT), "");
                String refusalText = textOrDefault(message.get(FIELD_REFUSAL), "");

                // tool_calls / legacy function_call → function_call output items
                ArrayNode toolCalls = null;
                if (message.has(FIELD_TOOL_CALLS) && message.get(FIELD_TOOL_CALLS).isArray()) {
                    toolCalls = (ArrayNode) message.get(FIELD_TOOL_CALLS);
                    for (JsonNode tc : toolCalls) {
                        if (isBlankText(tc.get(FIELD_ID))) {
                            log.debug("Chat→Responses: tool_call missing id, ignored");
                            continue;
                        }
                        ObjectNode funcItem = JSON.createObjectNode();
                        funcItem.put(OpenAiResponsesJsonPolicy.FIELD_CALL_ID, tc.get(FIELD_ID).asText());
                        funcItem.put(OpenAiResponsesJsonPolicy.FIELD_STATUS, OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
                        if (TYPE_CUSTOM.equals(tc.path(FIELD_TYPE).asText("")) && tc.has(FIELD_CUSTOM)) {
                            JsonNode custom = tc.get(FIELD_CUSTOM);
                            if (isBlankText(custom.get(FIELD_NAME))) {
                                log.debug("Chat→Responses: custom tool_call missing name, ignored");
                                continue;
                            }
                            funcItem.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_CUSTOM_TOOL_CALL);
                            funcItem.put(OpenAiResponsesJsonPolicy.FIELD_NAME, custom.get(FIELD_NAME).asText());
                            funcItem.put(FIELD_INPUT, textOrDefault(custom.get(FIELD_INPUT), ""));
                        } else if (tc.has(FIELD_FUNCTION)) {
                            JsonNode func = tc.get(FIELD_FUNCTION);
                            if (isBlankText(func.get(FIELD_NAME))) {
                                log.debug("Chat→Responses: function tool_call missing name, ignored");
                                continue;
                            }
                            funcItem.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL);
                            funcItem.put(OpenAiResponsesJsonPolicy.FIELD_NAME, func.get(FIELD_NAME).asText());
                            String args = textOrDefault(func.get(FIELD_ARGUMENTS), DEFAULT_FUNCTION_ARGUMENTS);
                            funcItem.put(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS,
                                    args.isEmpty() ? DEFAULT_FUNCTION_ARGUMENTS : args);
                        } else {
                            log.debug("Chat→Responses: tool_call missing function/custom payload, ignored");
                            continue;
                        }
                        output.add(funcItem);
                        hasToolCalls = true;
                    }
                } else if (message.has(FIELD_FUNCTION_CALL) && message.get(FIELD_FUNCTION_CALL).isObject()) {
                    JsonNode fc = message.get(FIELD_FUNCTION_CALL);
                    if (isBlankText(fc.get(FIELD_NAME))) {
                        log.debug("Chat→Responses: legacy function_call missing name, ignored");
                    } else {
                        ObjectNode funcItem = JSON.createObjectNode();
                        funcItem.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL);
                        String name = fc.get(FIELD_NAME).asText();
                        funcItem.put(OpenAiResponsesJsonPolicy.FIELD_CALL_ID, name);
                        funcItem.put(OpenAiResponsesJsonPolicy.FIELD_STATUS, OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
                        funcItem.put(OpenAiResponsesJsonPolicy.FIELD_NAME, name);
                        String args = textOrDefault(fc.get(FIELD_ARGUMENTS), DEFAULT_FUNCTION_ARGUMENTS);
                        funcItem.put(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS,
                                args.isEmpty() ? DEFAULT_FUNCTION_ARGUMENTS : args);
                        output.add(funcItem);
                        hasToolCalls = true;
                    }
                }

                // message item（包含文本内容）。纯 tool/reasoning 响应不伪造空文本 message。
                boolean hasMessagePayload = (refusalText != null && !refusalText.isEmpty())
                        || (contentText != null && !contentText.isEmpty());
                if (hasMessagePayload) {
                    ObjectNode msgItem = JSON.createObjectNode();
                    msgItem.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_MESSAGE);
                    msgItem.put(OpenAiResponsesJsonPolicy.FIELD_ROLE, OpenAiResponsesJsonPolicy.ROLE_ASSISTANT);
                    msgItem.put(OpenAiResponsesJsonPolicy.FIELD_STATUS, OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
                    ArrayNode msgContent = JSON.createArrayNode();
                    ObjectNode textPart = JSON.createObjectNode();
                    if (refusalText != null && !refusalText.isEmpty()) {
                        textPart.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_REFUSAL);
                        textPart.put(OpenAiResponsesJsonPolicy.FIELD_REFUSAL, refusalText);
                    } else {
                        textPart.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT);
                        textPart.put(OpenAiResponsesJsonPolicy.FIELD_TEXT, contentText != null ? contentText : "");
                    }
                    msgContent.add(textPart);
                    msgItem.set(OpenAiResponsesJsonPolicy.FIELD_CONTENT, msgContent);
                    output.add(msgItem);
                }
            }

            dst.set(OpenAiResponsesJsonPolicy.FIELD_OUTPUT, output);

            // finish_reason → status
            String status = mapChatFinishReasonToResponsesStatus(finishReason);
            dst.put(OpenAiResponsesJsonPolicy.FIELD_STATUS, status);
            if (OpenAiResponsesJsonPolicy.STATUS_INCOMPLETE.equals(status)) {
                ObjectNode incompleteDetails = JSON.createObjectNode();
                incompleteDetails.put(OpenAiResponsesJsonPolicy.FIELD_REASON,
                        mapChatFinishReasonToResponsesIncompleteReason(finishReason));
                dst.set(OpenAiResponsesJsonPolicy.FIELD_INCOMPLETE_DETAILS, incompleteDetails);
            }

            // usage
            if (src.has(FIELD_USAGE)) {
                JsonNode usage = src.get(FIELD_USAGE);
                ObjectNode respUsage = JSON.createObjectNode();
                int inputTokens = nonNegativeIntOrZero(usage.get(FIELD_PROMPT_TOKENS));
                int outputTokens = nonNegativeIntOrZero(usage.get(FIELD_COMPLETION_TOKENS));
                respUsage.put(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS, inputTokens);
                respUsage.put(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_TOKENS, outputTokens);
                int totalTokens = isNonNegativeInt(usage.get(FIELD_TOTAL_TOKENS))
                        ? usage.get(FIELD_TOTAL_TOKENS).asInt()
                        : inputTokens + outputTokens;
                respUsage.put(OpenAiResponsesJsonPolicy.FIELD_TOTAL_TOKENS, totalTokens);
                if (isPositiveInt(usage.path(FIELD_PROMPT_TOKENS_DETAILS).get(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS))) {
                    ObjectNode details = JSON.createObjectNode();
                    details.put(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS,
                            usage.get(FIELD_PROMPT_TOKENS_DETAILS).get(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS).asInt());
                    respUsage.set(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS, details);
                }
                if (isPositiveInt(usage.path(FIELD_COMPLETION_TOKENS_DETAILS).get(FIELD_REASONING_TOKENS))) {
                    ObjectNode details = JSON.createObjectNode();
                    details.put(FIELD_REASONING_TOKENS,
                            usage.get(FIELD_COMPLETION_TOKENS_DETAILS).get(FIELD_REASONING_TOKENS).asInt());
                    respUsage.set(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_TOKENS_DETAILS, details);
                }
                dst.set(OpenAiResponsesJsonPolicy.FIELD_USAGE, respUsage);
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

    private static String randomOpenAiResponsesIdSuffix() {
        return UUID.randomUUID().toString().replace("-", "")
                .substring(0, OpenAiResponsesJsonPolicy.RESPONSE_ID_RANDOM_LENGTH);
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
        private String currentItemType = OpenAiResponsesJsonPolicy.TYPE_MESSAGE;
        private String currentCallId = "";
        private String currentFunctionName = "";
        private final StringBuilder currentFunctionArguments = new StringBuilder();

        private boolean hasToolCalls = false;
        private String finishReason = FINISH_REASON_STOP;
        private int nextToolCallIndex = 0;
        private final List<String> pendingToolCallIds = new ArrayList<>();
        private final List<String> pendingToolCallNames = new ArrayList<>();
        private final Map<Integer, ToolCallState> toolCallStates = new LinkedHashMap<>();

        private int inputTokens = 0;
        private int outputTokens = 0;
        private int cachedTokens = 0;
        private int reasoningTokens = 0;

        ChatToIRStreamTranslator(String model) {
            this.model = model != null ? model : DEFAULT_MODEL;
            this.responseId = OpenAiResponsesJsonPolicy.RESPONSE_ID_PREFIX + randomOpenAiResponsesIdSuffix();
            this.createdAt = System.currentTimeMillis() / 1000;
            this.currentItemId = OpenAiResponsesJsonPolicy.MESSAGE_ID_PREFIX + randomOpenAiResponsesIdSuffix();
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

            String json = OpenAiResponsesSsePolicy.extractDataPayload(line);
            if (OpenAiResponsesSsePolicy.isDoneSentinel(json)) {
                // 流结束：关闭 open item，发送 completed
                closeOpenItem(output);
                closeOpenToolCalls(output);
                appendCompleted(output);
                done = true;
                return output;
            }

            if (json == null) return output;

            try {
                JsonNode chunk = JSON.readTree(json);
                if (isNonNegativeLong(chunk.get(FIELD_CREATED))) {
                    createdAt = chunk.get(FIELD_CREATED).asLong();
                }
                if (!chunk.has(FIELD_CHOICES) || chunk.get(FIELD_CHOICES).size() == 0) {
                    // usage-only chunk
                    if (chunk.has(FIELD_USAGE)) {
                        extractUsage(chunk.get(FIELD_USAGE));
                    }
                    return output;
                }

                JsonNode choice = chunk.get(FIELD_CHOICES).get(0);
                JsonNode delta = choice.has(FIELD_DELTA) ? choice.get(FIELD_DELTA) : null;

                if (chunk.has(FIELD_USAGE)) {
                    extractUsage(chunk.get(FIELD_USAGE));
                }

                // role → 只触发 response.created；具体 output item 等到文本、推理或工具调用出现时再创建。
                if (delta != null && delta.has(FIELD_ROLE)) {
                    ensureCreatedSent(output);
                }

                // content → output_text.delta
                if (delta != null && delta.has(FIELD_CONTENT) && !delta.get(FIELD_CONTENT).isNull()) {
                    String text = textOrDefault(delta.get(FIELD_CONTENT), "");
                    if (!text.isEmpty()) {
                        if (outputItemAdded && !OpenAiResponsesJsonPolicy.TYPE_MESSAGE.equals(currentItemType)) {
                            if (closeOpenItem(output)) outputIndex++;
                        }
                        if (!outputItemAdded) {
                            ensureCreatedSent(output);
                            currentItemId = OpenAiResponsesJsonPolicy.MESSAGE_ID_PREFIX + randomOpenAiResponsesIdSuffix();
                            appendMessageItemAdded(output, currentItemId);
                            outputItemAdded = true;
                            currentItemType = OpenAiResponsesJsonPolicy.TYPE_MESSAGE;
                        }
                        appendContentDelta(output, OpenAiResponsesSsePolicy.EVENT_OUTPUT_TEXT_DELTA, currentItemId, text);
                    }
                }

                // refusal → refusal.delta
                if (delta != null && delta.has(FIELD_REFUSAL) && !delta.get(FIELD_REFUSAL).isNull()) {
                    String refusal = textOrDefault(delta.get(FIELD_REFUSAL), "");
                    if (!refusal.isEmpty()) {
                        if (outputItemAdded && !OpenAiResponsesJsonPolicy.TYPE_MESSAGE.equals(currentItemType)) {
                            if (closeOpenItem(output)) outputIndex++;
                        }
                        if (!outputItemAdded) {
                            ensureCreatedSent(output);
                            currentItemId = OpenAiResponsesJsonPolicy.MESSAGE_ID_PREFIX + randomOpenAiResponsesIdSuffix();
                            appendMessageItemAdded(output, currentItemId);
                            outputItemAdded = true;
                            currentItemType = OpenAiResponsesJsonPolicy.TYPE_MESSAGE;
                        }
                        appendContentDelta(output, OpenAiResponsesSsePolicy.EVENT_REFUSAL_DELTA, currentItemId, refusal);
                    }
                }

                // reasoning_content → reasoning_summary_text.delta
                if (delta != null && delta.has(FIELD_REASONING_CONTENT) && !delta.get(FIELD_REASONING_CONTENT).isNull()) {
                    String rc = textOrDefault(delta.get(FIELD_REASONING_CONTENT), "");
                    if (!rc.isEmpty()) {
                        if (outputItemAdded && !OpenAiResponsesJsonPolicy.TYPE_REASONING.equals(currentItemType)) {
                            if (closeOpenItem(output)) outputIndex++;
                        }
                        if (!outputItemAdded) {
                            ensureCreatedSent(output);
                            currentItemId = OpenAiResponsesJsonPolicy.REASONING_ID_PREFIX + randomOpenAiResponsesIdSuffix();
                            appendReasoningItemAdded(output, currentItemId);
                            outputItemAdded = true;
                            currentItemType = OpenAiResponsesJsonPolicy.TYPE_REASONING;
                        }
                        appendReasoningDelta(output, currentItemId, rc);
                    }
                }

                // tool_calls → output_item.added + function_call_arguments.delta
                // Chat 流式工具调用可按 index 并行/交错输出，因此每个 index 独立累积参数。
                if (delta != null && delta.has(FIELD_TOOL_CALLS) && delta.get(FIELD_TOOL_CALLS).isArray()) {
                    for (JsonNode tc : delta.get(FIELD_TOOL_CALLS)) {
                        int tcIndex = nonNegativeIntOrDefault(tc.get(FIELD_INDEX), nextToolCallIndex);

                        // 新增 tool_call
                        if (tc.has(FIELD_ID) && tc.has(FIELD_FUNCTION) && tc.get(FIELD_FUNCTION).has(FIELD_NAME)) {
                            String callId = textOrDefault(tc.get(FIELD_ID), "");
                            String funcName = textOrDefault(tc.get(FIELD_FUNCTION).get(FIELD_NAME), "");
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
                        if (tc.has(FIELD_FUNCTION) && tc.get(FIELD_FUNCTION).has(FIELD_ARGUMENTS)) {
                            String args = textOrDefault(tc.get(FIELD_FUNCTION).get(FIELD_ARGUMENTS), "");
                            if (!args.isEmpty()) {
                                ToolCallState state = toolCallStates.get(tcIndex);
                                if (state == null) {
                                    log.debug("Chat→Responses stream: tool_call arguments without valid tool_call ignored");
                                    continue;
                                }
                                state.arguments.append(args);
                                appendFunctionArgumentsDelta(output, state.itemId, state.outputIndex, args);
                            }
                        }
                    }
                }

                // legacy function_call → function_call item
                if (delta != null && delta.has(FIELD_FUNCTION_CALL) && delta.get(FIELD_FUNCTION_CALL).isObject()) {
                    JsonNode fc = delta.get(FIELD_FUNCTION_CALL);
                    int tcIndex = 0;
                    String funcName = textOrDefault(fc.get(FIELD_NAME), "");
                    ToolCallState state = toolCallStates.get(tcIndex);
                    if (!funcName.isBlank()) {
                        state = ensureToolCallState(output, tcIndex, funcName, funcName);
                        hasToolCalls = true;
                    } else if (state == null) {
                        log.debug("Chat→Responses stream: legacy function_call missing name, ignored");
                        return output;
                    }
                    if (fc.has(FIELD_ARGUMENTS)) {
                        String args = textOrDefault(fc.get(FIELD_ARGUMENTS), "");
                        if (!args.isEmpty()) {
                            state.arguments.append(args);
                            appendFunctionArgumentsDelta(output, state.itemId, state.outputIndex, args);
                        }
                    }
                }

                // finish_reason → 关闭当前 item；等待后续 usage-only chunk 和最终 [DONE] 再完成响应。
                if (choice.has(FIELD_FINISH_REASON) && !choice.get(FIELD_FINISH_REASON).isNull()
                        && choice.get(FIELD_FINISH_REASON).isTextual()
                        && !"null".equals(choice.get(FIELD_FINISH_REASON).asText())) {
                    finishReason = choice.get(FIELD_FINISH_REASON).asText();
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
                if (existing.callId == null || existing.callId.isBlank()
                        || existing.callId.startsWith(ID_PREFIX_TOOL_CALL)) {
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

            String itemId = OpenAiResponsesJsonPolicy.ITEM_ID_PREFIX + randomOpenAiResponsesIdSuffix();
            ToolCallState state = new ToolCallState(outputIndex++, itemId, callId, funcName);
            toolCallStates.put(tcIndex, state);

            ensureCreatedSent(output);
            appendFunctionCallItemAdded(output, state);
            return state;
        }

        private void closeOpenToolCalls(List<String> output) {
            for (ToolCallState state : toolCallStates.values()) {
                if (state.done) continue;
                String args = state.arguments.length() > 0 ? state.arguments.toString() : DEFAULT_FUNCTION_ARGUMENTS;
                appendFunctionArgumentsDone(output, state.itemId, state.outputIndex, args);
                appendFunctionCallItemDone(output, state.itemId, state.outputIndex, state.callId, state.name, args);
                state.done = true;
            }
        }

        private void ensureCreatedSent(List<String> output) {
            if (!createdSent) {
                ObjectNode event = baseResponsesEvent(OpenAiResponsesSsePolicy.EVENT_RESPONSE_CREATED);
                ObjectNode response = JSON.createObjectNode();
                response.put(OpenAiResponsesJsonPolicy.FIELD_ID, responseId);
                response.put(OpenAiResponsesJsonPolicy.FIELD_OBJECT, OpenAiResponsesJsonPolicy.OBJECT_RESPONSE);
                response.put(OpenAiResponsesJsonPolicy.FIELD_MODEL, model);
                response.put(OpenAiResponsesJsonPolicy.FIELD_CREATED_AT, createdAt);
                response.put(OpenAiResponsesJsonPolicy.FIELD_STATUS, OpenAiResponsesJsonPolicy.STATUS_IN_PROGRESS);
                response.set(OpenAiResponsesJsonPolicy.FIELD_OUTPUT, JSON.createArrayNode());
                response.putNull(OpenAiResponsesJsonPolicy.FIELD_USAGE);
                event.set(OpenAiResponsesJsonPolicy.FIELD_RESPONSE, response);
                appendJsonEvent(output, OpenAiResponsesSsePolicy.EVENT_RESPONSE_CREATED, event);
                createdSent = true;
            }
        }

        private boolean closeOpenItem(List<String> output) {
            if (outputItemAdded) {
                if (OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL.equals(currentItemType)) {
                    String args = currentFunctionArguments.length() > 0
                            ? currentFunctionArguments.toString()
                            : DEFAULT_FUNCTION_ARGUMENTS;
                    appendFunctionArgumentsDone(output, currentItemId, outputIndex, args);
                    appendFunctionCallItemDone(output, currentItemId, outputIndex,
                            currentCallId, currentFunctionName, args);
                } else if (OpenAiResponsesJsonPolicy.TYPE_REASONING.equals(currentItemType)) {
                    appendReasoningDone(output, currentItemId);
                    ObjectNode item = responseItem(currentItemId, OpenAiResponsesJsonPolicy.TYPE_REASONING,
                            OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
                    appendOutputItemDone(output, outputIndex, item);
                } else {
                    ObjectNode item = responseItem(currentItemId, OpenAiResponsesJsonPolicy.TYPE_MESSAGE,
                            OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
                    item.put(OpenAiResponsesJsonPolicy.FIELD_ROLE, OpenAiResponsesJsonPolicy.ROLE_ASSISTANT);
                    appendOutputItemDone(output, outputIndex, item);
                }
                outputItemAdded = false;
                currentItemType = OpenAiResponsesJsonPolicy.TYPE_MESSAGE;
                currentCallId = "";
                currentFunctionName = "";
                currentFunctionArguments.setLength(0);
                return true;
            }
            return false;
        }

        private void appendCompleted(List<String> output) {
            String status = OpenAiChatCompletionsBodyPolicy.isIncompleteFinishReason(finishReason)
                    ? OpenAiResponsesJsonPolicy.STATUS_INCOMPLETE
                    : OpenAiResponsesJsonPolicy.STATUS_COMPLETED;
            String eventType = OpenAiResponsesJsonPolicy.STATUS_INCOMPLETE.equals(status)
                    ? OpenAiResponsesSsePolicy.EVENT_RESPONSE_INCOMPLETE
                    : OpenAiResponsesSsePolicy.EVENT_RESPONSE_COMPLETED;
            ObjectNode event = baseResponsesEvent(eventType);
            ObjectNode response = JSON.createObjectNode();
            response.put(OpenAiResponsesJsonPolicy.FIELD_ID, responseId);
            response.put(OpenAiResponsesJsonPolicy.FIELD_OBJECT, OpenAiResponsesJsonPolicy.OBJECT_RESPONSE);
            response.put(OpenAiResponsesJsonPolicy.FIELD_MODEL, model);
            response.put(OpenAiResponsesJsonPolicy.FIELD_CREATED_AT, createdAt);
            response.put(OpenAiResponsesJsonPolicy.FIELD_STATUS, status);
            if (OpenAiResponsesJsonPolicy.STATUS_INCOMPLETE.equals(status)) {
                ObjectNode incompleteDetails = JSON.createObjectNode();
                incompleteDetails.put(OpenAiResponsesJsonPolicy.FIELD_REASON,
                        mapChatFinishReasonToResponsesIncompleteReason(finishReason));
                response.set(OpenAiResponsesJsonPolicy.FIELD_INCOMPLETE_DETAILS, incompleteDetails);
            }
            response.set(OpenAiResponsesJsonPolicy.FIELD_USAGE, responseUsage());
            event.set(OpenAiResponsesJsonPolicy.FIELD_RESPONSE, response);
            appendJsonEvent(output, eventType, event);
        }

        private void appendMessageItemAdded(List<String> output, String itemId) {
            ObjectNode item = responseItem(itemId, OpenAiResponsesJsonPolicy.TYPE_MESSAGE,
                    OpenAiResponsesJsonPolicy.STATUS_IN_PROGRESS);
            item.put(OpenAiResponsesJsonPolicy.FIELD_ROLE, OpenAiResponsesJsonPolicy.ROLE_ASSISTANT);
            item.set(OpenAiResponsesJsonPolicy.FIELD_CONTENT, JSON.createArrayNode());
            appendOutputItemAdded(output, outputIndex, item);
        }

        private void appendReasoningItemAdded(List<String> output, String itemId) {
            ObjectNode item = responseItem(itemId, OpenAiResponsesJsonPolicy.TYPE_REASONING,
                    OpenAiResponsesJsonPolicy.STATUS_IN_PROGRESS);
            item.set(OpenAiResponsesJsonPolicy.FIELD_SUMMARY, JSON.createArrayNode());
            appendOutputItemAdded(output, outputIndex, item);
        }

        private void appendFunctionCallItemAdded(List<String> output, ToolCallState state) {
            ObjectNode item = responseItem(state.itemId, OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL,
                    OpenAiResponsesJsonPolicy.STATUS_IN_PROGRESS);
            item.put(OpenAiResponsesJsonPolicy.FIELD_CALL_ID, state.callId);
            item.put(OpenAiResponsesJsonPolicy.FIELD_NAME, state.name);
            appendOutputItemAdded(output, state.outputIndex, item);
        }

        private void appendOutputItemAdded(List<String> output, int itemOutputIndex, ObjectNode item) {
            ObjectNode event = baseResponseIdEvent(OpenAiResponsesSsePolicy.EVENT_OUTPUT_ITEM_ADDED);
            event.put(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX, itemOutputIndex);
            event.set(OpenAiResponsesJsonPolicy.FIELD_ITEM, item);
            appendJsonEvent(output, OpenAiResponsesSsePolicy.EVENT_OUTPUT_ITEM_ADDED, event);
        }

        private void appendOutputItemDone(List<String> output, int itemOutputIndex, ObjectNode item) {
            ObjectNode event = baseResponseIdEvent(OpenAiResponsesSsePolicy.EVENT_OUTPUT_ITEM_DONE);
            event.put(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX, itemOutputIndex);
            event.set(OpenAiResponsesJsonPolicy.FIELD_ITEM, item);
            appendJsonEvent(output, OpenAiResponsesSsePolicy.EVENT_OUTPUT_ITEM_DONE, event);
        }

        private void appendContentDelta(List<String> output, String eventType, String itemId, String delta) {
            ObjectNode event = baseItemEvent(eventType, itemId, outputIndex);
            event.put(OpenAiResponsesJsonPolicy.FIELD_CONTENT_INDEX, contentIndex);
            event.put(OpenAiResponsesJsonPolicy.FIELD_DELTA, delta);
            appendJsonEvent(output, eventType, event);
        }

        private void appendReasoningDelta(List<String> output, String itemId, String delta) {
            ObjectNode event = baseItemEvent(OpenAiResponsesSsePolicy.EVENT_REASONING_SUMMARY_TEXT_DELTA,
                    itemId, outputIndex);
            event.put(OpenAiResponsesJsonPolicy.FIELD_SUMMARY_INDEX, 0);
            event.put(OpenAiResponsesJsonPolicy.FIELD_DELTA, delta);
            appendJsonEvent(output, OpenAiResponsesSsePolicy.EVENT_REASONING_SUMMARY_TEXT_DELTA, event);
        }

        private void appendReasoningDone(List<String> output, String itemId) {
            ObjectNode event = baseItemEvent(OpenAiResponsesSsePolicy.EVENT_REASONING_SUMMARY_TEXT_DONE,
                    itemId, outputIndex);
            event.put(OpenAiResponsesJsonPolicy.FIELD_SUMMARY_INDEX, 0);
            appendJsonEvent(output, OpenAiResponsesSsePolicy.EVENT_REASONING_SUMMARY_TEXT_DONE, event);
        }

        private void appendFunctionArgumentsDelta(List<String> output, String itemId, int itemOutputIndex, String delta) {
            ObjectNode event = baseItemEvent(OpenAiResponsesSsePolicy.EVENT_FUNCTION_CALL_ARGUMENTS_DELTA,
                    itemId, itemOutputIndex);
            event.put(OpenAiResponsesJsonPolicy.FIELD_DELTA, delta);
            appendJsonEvent(output, OpenAiResponsesSsePolicy.EVENT_FUNCTION_CALL_ARGUMENTS_DELTA, event);
        }

        private void appendFunctionArgumentsDone(List<String> output, String itemId, int itemOutputIndex, String arguments) {
            ObjectNode event = baseItemEvent(OpenAiResponsesSsePolicy.EVENT_FUNCTION_CALL_ARGUMENTS_DONE,
                    itemId, itemOutputIndex);
            event.put(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS, arguments);
            appendJsonEvent(output, OpenAiResponsesSsePolicy.EVENT_FUNCTION_CALL_ARGUMENTS_DONE, event);
        }

        private void appendFunctionCallItemDone(List<String> output, String itemId, int itemOutputIndex,
                                                String callId, String name, String arguments) {
            ObjectNode item = responseItem(itemId, OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL,
                    OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
            item.put(OpenAiResponsesJsonPolicy.FIELD_CALL_ID, callId);
            item.put(OpenAiResponsesJsonPolicy.FIELD_NAME, name);
            item.put(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS, arguments);
            appendOutputItemDone(output, itemOutputIndex, item);
        }

        private ObjectNode baseResponsesEvent(String eventType) {
            ObjectNode event = JSON.createObjectNode();
            event.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, eventType);
            event.put(OpenAiResponsesJsonPolicy.FIELD_SEQUENCE_NUMBER, sequenceNumber++);
            return event;
        }

        private ObjectNode baseResponseIdEvent(String eventType) {
            ObjectNode event = baseResponsesEvent(eventType);
            event.put(OpenAiResponsesJsonPolicy.FIELD_RESPONSE_ID, responseId);
            return event;
        }

        private ObjectNode baseItemEvent(String eventType, String itemId, int itemOutputIndex) {
            ObjectNode event = baseResponseIdEvent(eventType);
            event.put(OpenAiResponsesJsonPolicy.FIELD_ITEM_ID, itemId);
            event.put(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX, itemOutputIndex);
            return event;
        }

        private ObjectNode responseItem(String itemId, String type, String status) {
            ObjectNode item = JSON.createObjectNode();
            item.put(OpenAiResponsesJsonPolicy.FIELD_ID, itemId);
            item.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, type);
            item.put(OpenAiResponsesJsonPolicy.FIELD_STATUS, status);
            return item;
        }

        private ObjectNode responseUsage() {
            ObjectNode usage = JSON.createObjectNode();
            usage.put(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS, inputTokens);
            usage.put(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_TOKENS, outputTokens);
            usage.put(OpenAiResponsesJsonPolicy.FIELD_TOTAL_TOKENS, inputTokens + outputTokens);
            if (cachedTokens > 0) {
                ObjectNode details = JSON.createObjectNode();
                details.put(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS, cachedTokens);
                usage.set(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS, details);
            }
            if (reasoningTokens > 0) {
                ObjectNode details = JSON.createObjectNode();
                details.put(FIELD_REASONING_TOKENS, reasoningTokens);
                usage.set(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_TOKENS_DETAILS, details);
            }
            return usage;
        }

        private void appendJsonEvent(List<String> output, String eventType, ObjectNode event) {
            appendEvent(output, eventType, event.toString());
        }

        private void extractUsage(JsonNode usage) {
            if (isNonNegativeInt(usage.get(FIELD_PROMPT_TOKENS))) inputTokens = usage.get(FIELD_PROMPT_TOKENS).asInt();
            if (isNonNegativeInt(usage.get(FIELD_COMPLETION_TOKENS))) {
                outputTokens = usage.get(FIELD_COMPLETION_TOKENS).asInt();
            }
            if (usage.has(FIELD_PROMPT_TOKENS_DETAILS)
                    && isNonNegativeInt(usage.get(FIELD_PROMPT_TOKENS_DETAILS)
                    .get(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS))) {
                cachedTokens = usage.get(FIELD_PROMPT_TOKENS_DETAILS)
                        .get(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS).asInt();
            }
            if (usage.has(FIELD_COMPLETION_TOKENS_DETAILS)
                    && isNonNegativeInt(usage.get(FIELD_COMPLETION_TOKENS_DETAILS).get(FIELD_REASONING_TOKENS))) {
                reasoningTokens = usage.get(FIELD_COMPLETION_TOKENS_DETAILS).get(FIELD_REASONING_TOKENS).asInt();
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
        item.put(FIELD_ROLE, role);

        if (contentNode == null) {
            item.put(FIELD_CONTENT, "");
        } else if (contentNode.isTextual()) {
            item.put(FIELD_CONTENT, contentNode.asText());
        } else if (contentNode.isArray()) {
            ArrayNode parts = JSON.createArrayNode();
            for (JsonNode part : contentNode) {
                appendChatInputContentPart(part, parts, false);
            }
            if (parts.size() == 0) {
                return;
            }
            item.set(FIELD_CONTENT, parts);
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
        item.put(FIELD_ROLE, ROLE_USER);

        if (contentNode == null) {
            item.put(FIELD_CONTENT, "");
        } else if (contentNode.isTextual()) {
            item.put(FIELD_CONTENT, contentNode.asText());
        } else if (contentNode.isArray()) {
            ArrayNode parts = JSON.createArrayNode();
            for (JsonNode part : contentNode) {
                appendChatInputContentPart(part, parts, true);
            }
            if (parts.size() == 0) {
                return;
            }
            item.set(FIELD_CONTENT, parts);
        } else {
            return;
        }
        input.add(item);
    }

    private static void appendChatInputContentPart(JsonNode part, ArrayNode parts, boolean includeLandGateExtensions) {
        String partType = textOrDefault(part.get(FIELD_TYPE), "");
        switch (partType) {
            case TYPE_TEXT -> {
                if (isBlankText(part.get(FIELD_TEXT))) {
                    break;
                }
                ObjectNode p = JSON.createObjectNode();
                p.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_INPUT_TEXT);
                p.put(OpenAiResponsesJsonPolicy.FIELD_TEXT, part.get(FIELD_TEXT).asText());
                parts.add(p);
            }
            case TYPE_IMAGE_URL -> {
                JsonNode imageUrl = part.get(FIELD_IMAGE_URL);
                if (imageUrl != null && imageUrl.isObject() && !isBlankText(imageUrl.get(FIELD_URL))) {
                    String url = imageUrl.get(FIELD_URL).asText();
                    if (!isEmptyBase64DataURI(url)) {
                        ObjectNode p = JSON.createObjectNode();
                        p.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TYPE_INPUT_IMAGE);
                        p.put(OpenAiResponsesBodyPolicy.FIELD_IMAGE_URL, url);
                        String detail = normalizeImageDetail(imageUrl.get(FIELD_DETAIL));
                        if (detail != null) {
                            p.put(FIELD_DETAIL, detail);
                        }
                        parts.add(p);
                    }
                }
            }
            case TYPE_INPUT_AUDIO -> {
                if (!includeLandGateExtensions) {
                    break;
                }
                JsonNode inputAudio = part.get(FIELD_INPUT_AUDIO);
                if (inputAudio != null && inputAudio.isObject() && hasChatAudioPayload(inputAudio)) {
                    ObjectNode p = JSON.createObjectNode();
                    p.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, TYPE_INPUT_AUDIO);
                    p.set(FIELD_INPUT_AUDIO, inputAudio);
                    parts.add(p);
                }
            }
            case TYPE_FILE -> {
                if (!includeLandGateExtensions) {
                    break;
                }
                JsonNode file = part.get(FIELD_FILE);
                if (file != null && file.isObject() && hasChatFilePayload(file)) {
                    ObjectNode p = JSON.createObjectNode();
                    p.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_INPUT_FILE);
                    copyTextIfExists(file, p, FIELD_FILE_DATA);
                    copyTextIfExists(file, p, FIELD_FILE_ID);
                    copyTextIfExists(file, p, FIELD_FILENAME);
                    parts.add(p);
                }
            }
            default -> log.debug("Chat→Responses: unknown content part type '{}'", partType);
        }
    }

    /**
     * Assistant 消息 → assistant message item + function_call items。
     */
    private static void convertChatAssistantMessage(JsonNode msg, ArrayNode input) {
        // 对齐 sub2api：assistant 历史 thinking/reasoning content 作为显式标签文本保留，
        // 不构造 Responses reasoning input item，避免 Codex internal 对输入 item 类型挑剔。
        String contentText = parseAssistantContent(msg.get(FIELD_CONTENT));
        String reasoningContent = textOrDefault(msg.get(FIELD_REASONING_CONTENT), "");

        StringBuilder fullContent = new StringBuilder();
        if (contentText != null && !contentText.isEmpty()) {
            fullContent.append(contentText);
        }
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            fullContent.append(THINKING_OPEN_TAG).append(reasoningContent).append(THINKING_CLOSE_TAG);
        }

        // assistant message item（非空文本时）
        if (fullContent.length() > 0) {
            ObjectNode item = JSON.createObjectNode();
            item.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_MESSAGE);
            item.put(FIELD_ROLE, ROLE_ASSISTANT);
            ArrayNode parts = JSON.createArrayNode();
            ObjectNode part = JSON.createObjectNode();
            part.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT);
            part.put(OpenAiResponsesJsonPolicy.FIELD_TEXT, fullContent.toString());
            parts.add(part);
            item.set(FIELD_CONTENT, parts);
            input.add(item);
        }

        // tool_calls → function_call/custom_tool_call items
        if (msg.has(FIELD_TOOL_CALLS) && msg.get(FIELD_TOOL_CALLS).isArray()) {
            for (JsonNode tc : msg.get(FIELD_TOOL_CALLS)) {
                if (isBlankText(tc.get(FIELD_ID))) {
                    log.debug("Chat→Responses request: tool_call missing id, ignored");
                    continue;
                }
                ObjectNode toolItem = JSON.createObjectNode();
                toolItem.put(OpenAiResponsesJsonPolicy.FIELD_CALL_ID, tc.get(FIELD_ID).asText());
                if (TYPE_CUSTOM.equals(tc.path(FIELD_TYPE).asText("")) && tc.has(FIELD_CUSTOM)) {
                    JsonNode custom = tc.get(FIELD_CUSTOM);
                    if (isBlankText(custom.get(FIELD_NAME))) {
                        log.debug("Chat→Responses request: custom tool_call missing name, ignored");
                        continue;
                    }
                    toolItem.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_CUSTOM_TOOL_CALL);
                    toolItem.put(OpenAiResponsesJsonPolicy.FIELD_NAME, custom.get(FIELD_NAME).asText());
                    toolItem.put(FIELD_INPUT, textOrDefault(custom.get(FIELD_INPUT), ""));
                } else if (tc.has(FIELD_FUNCTION)) {
                    JsonNode func = tc.get(FIELD_FUNCTION);
                    if (isBlankText(func.get(FIELD_NAME))) {
                        log.debug("Chat→Responses request: function tool_call missing name, ignored");
                        continue;
                    }
                    toolItem.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL);
                    toolItem.put(OpenAiResponsesJsonPolicy.FIELD_NAME, func.get(FIELD_NAME).asText());
                    String args = textOrDefault(func.get(FIELD_ARGUMENTS), DEFAULT_FUNCTION_ARGUMENTS);
                    toolItem.put(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS,
                            args.isEmpty() ? DEFAULT_FUNCTION_ARGUMENTS : args);
                } else {
                    log.debug("Chat→Responses request: tool_call missing function/custom payload, ignored");
                    continue;
                }
                input.add(toolItem);
            }
        } else if (msg.has(FIELD_FUNCTION_CALL) && msg.get(FIELD_FUNCTION_CALL).isObject()) {
            JsonNode fc = msg.get(FIELD_FUNCTION_CALL);
            if (isBlankText(fc.get(FIELD_NAME))) {
                log.debug("Chat→Responses request: legacy function_call missing name, ignored");
                return;
            }
            ObjectNode funcItem = JSON.createObjectNode();
            funcItem.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL);
            String name = fc.get(FIELD_NAME).asText();
            funcItem.put(OpenAiResponsesJsonPolicy.FIELD_CALL_ID, name);
            funcItem.put(OpenAiResponsesJsonPolicy.FIELD_NAME, name);
            String args = textOrDefault(fc.get(FIELD_ARGUMENTS), DEFAULT_FUNCTION_ARGUMENTS);
            funcItem.put(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS,
                    args.isEmpty() ? DEFAULT_FUNCTION_ARGUMENTS : args);
            input.add(funcItem);
        }
    }

    /**
     * Tool 消息（role="tool"）→ function_call_output item。
     */
    private static void convertChatToolMessage(JsonNode msg, ArrayNode input) {
        if (isBlankText(msg.get(FIELD_TOOL_CALL_ID))) {
            log.debug("Chat→Responses request: tool message missing tool_call_id, ignored");
            return;
        }
        ObjectNode item = JSON.createObjectNode();
        item.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TYPE_FUNCTION_CALL_OUTPUT);
        item.put(OpenAiResponsesJsonPolicy.FIELD_CALL_ID, msg.get(FIELD_TOOL_CALL_ID).asText());
        String output = flattenContent(msg.get(FIELD_CONTENT));
        if (output.isEmpty()) {
            output = DEFAULT_EMPTY_TOOL_OUTPUT;
        }
        item.put(OpenAiResponsesJsonPolicy.FIELD_OUTPUT, output);
        input.add(item);
    }

    /**
     * Function 消息（旧式 role="function"）→ function_call_output item。
     * 使用 name 字段作为 call_id（旧格式无独立 tool_call_id）。
     */
    private static void convertChatFunctionMessage(JsonNode msg, ArrayNode input) {
        if (isBlankText(msg.get(FIELD_NAME))) {
            log.debug("Chat→Responses request: legacy function message missing name, ignored");
            return;
        }
        ObjectNode item = JSON.createObjectNode();
        item.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TYPE_FUNCTION_CALL_OUTPUT);
        item.put(OpenAiResponsesJsonPolicy.FIELD_CALL_ID, msg.get(FIELD_NAME).asText());
        String output = flattenContent(msg.get(FIELD_CONTENT));
        if (output.isEmpty()) {
            output = DEFAULT_EMPTY_TOOL_OUTPUT;
        }
        item.put(OpenAiResponsesJsonPolicy.FIELD_OUTPUT, output);
        input.add(item);
    }

    /**
     * 解析 assistant content。
     * <p>
     * 官方 Chat assistant content part 仅按文本/拒绝文本处理；thinking/reasoning 是兼容扩展，
     * 需要作为 Responses reasoning item 保留，不能伪造成 output_text。
     */
    private static String parseAssistantContent(JsonNode content) {
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String partType = textOrDefault(part.get(FIELD_TYPE), "");
                if (OpenAiChatCompletionsBodyPolicy.TYPE_THINKING.equals(partType) || TYPE_REASONING.equals(partType)) {
                    String thinkingText = textOrDefault(part.get(OpenAiChatCompletionsBodyPolicy.FIELD_THINKING),
                            textOrDefault(part.get(FIELD_TEXT), ""));
                    if (!thinkingText.isEmpty()) {
                        sb.append(THINKING_OPEN_TAG).append(thinkingText).append(THINKING_CLOSE_TAG);
                    }
                } else if (TYPE_REFUSAL.equals(partType) && !isBlankText(part.get(FIELD_REFUSAL))) {
                    sb.append(part.get(FIELD_REFUSAL).asText());
                } else if (!isBlankText(part.get(FIELD_TEXT))) {
                    sb.append(part.get(FIELD_TEXT).asText());
                }
            }
            return sb.toString();
        }
        return "";
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
                if (!isBlankText(part.get(FIELD_TEXT))) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(part.get(FIELD_TEXT).asText());
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
        return OpenAiChatCompletionsBodyPolicy.isIncompleteFinishReason(finishReason)
                ? OpenAiResponsesJsonPolicy.STATUS_INCOMPLETE
                : OpenAiResponsesJsonPolicy.STATUS_COMPLETED;
    }

    private static String mapChatFinishReasonToResponsesIncompleteReason(String finishReason) {
        return FINISH_REASON_CONTENT_FILTER.equals(finishReason)
                ? FINISH_REASON_CONTENT_FILTER
                : OpenAiResponsesJsonPolicy.DEFAULT_INCOMPLETE_REASON;
    }

    /**
     * 旧式 function_call → tool_choice。
     * "auto"→"auto", "none"→"none", {"name":"X"}→{"type":"function","name":"X"}
     */
    private static JsonNode convertLegacyFunctionCall(JsonNode functionCall) {
        if (functionCall.isTextual()) {
            return functionCall;
        }
        if (functionCall.isObject()) {
            ObjectNode obj = JSON.createObjectNode();
            obj.put(FIELD_TYPE, TYPE_FUNCTION);
            obj.put(FIELD_NAME, functionCall.has(FIELD_NAME) && functionCall.get(FIELD_NAME).isTextual()
                    ? functionCall.get(FIELD_NAME).asText()
                    : "");
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
                && TYPE_FUNCTION.equals(toolChoice.path(FIELD_TYPE).asText(""))
                && toolChoice.has(FIELD_FUNCTION)
                && toolChoice.get(FIELD_FUNCTION).isObject()) {
            if (isBlankText(toolChoice.get(FIELD_FUNCTION).get(FIELD_NAME))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put(FIELD_TYPE, TYPE_FUNCTION);
            obj.put(FIELD_NAME, toolChoice.get(FIELD_FUNCTION).get(FIELD_NAME).asText());
            return obj;
        }
        if (toolChoice.isObject()
                && TYPE_CUSTOM.equals(toolChoice.path(FIELD_TYPE).asText(""))
                && toolChoice.has(FIELD_CUSTOM)
                && toolChoice.get(FIELD_CUSTOM).isObject()) {
            if (isBlankText(toolChoice.get(FIELD_CUSTOM).get(FIELD_NAME))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put(FIELD_TYPE, TYPE_CUSTOM);
            obj.put(FIELD_NAME, toolChoice.get(FIELD_CUSTOM).get(FIELD_NAME).asText());
            return obj;
        }
        if (toolChoice.isObject()
                && TYPE_ALLOWED_TOOLS.equals(toolChoice.path(FIELD_TYPE).asText(""))
                && toolChoice.has(FIELD_ALLOWED_TOOLS)
                && toolChoice.get(FIELD_ALLOWED_TOOLS).isObject()) {
            ObjectNode obj = JSON.createObjectNode();
            obj.put(FIELD_TYPE, TYPE_ALLOWED_TOOLS);
            JsonNode allowed = toolChoice.get(FIELD_ALLOWED_TOOLS);
            String mode = normalizeAllowedToolsMode(allowed.get(FIELD_MODE));
            if (mode != null) {
                obj.put(FIELD_MODE, mode);
            }
            if (allowed.has(FIELD_TOOLS) && allowed.get(FIELD_TOOLS).isArray()) {
                ArrayNode tools = JSON.createArrayNode();
                for (JsonNode tool : allowed.get(FIELD_TOOLS)) {
                    JsonNode converted = convertChatAllowedToolToResponses(tool);
                    if (converted != null) tools.add(converted);
                }
                if (tools.size() == 0) return null;
                obj.set(FIELD_TOOLS, tools);
            }
            return obj;
        }
        return null;
    }

    private static boolean isSupportedToolChoiceMode(String mode) {
        return OpenAiChatCompletionsBodyPolicy.isSupportedToolChoiceMode(mode);
    }

    private static JsonNode convertChatAllowedToolToResponses(JsonNode tool) {
        if (tool == null || !tool.isObject()) return null;
        String type = tool.path(FIELD_TYPE).asText("");
        if (TYPE_FUNCTION.equals(type) && tool.has(FIELD_FUNCTION) && tool.get(FIELD_FUNCTION).isObject()) {
            if (isBlankText(tool.get(FIELD_FUNCTION).get(FIELD_NAME))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put(FIELD_TYPE, TYPE_FUNCTION);
            obj.put(FIELD_NAME, tool.get(FIELD_FUNCTION).path(FIELD_NAME).asText(""));
            return obj;
        }
        if (TYPE_CUSTOM.equals(type) && tool.has(FIELD_CUSTOM) && tool.get(FIELD_CUSTOM).isObject()) {
            if (isBlankText(tool.get(FIELD_CUSTOM).get(FIELD_NAME))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put(FIELD_TYPE, TYPE_CUSTOM);
            obj.put(FIELD_NAME, tool.get(FIELD_CUSTOM).path(FIELD_NAME).asText(""));
            return obj;
        }
        return null;
    }

    private static JsonNode convertChatCustomToolFormatToResponses(JsonNode format) {
        if (format == null || !format.isObject() || isBlankText(format.get(FIELD_TYPE))) return null;
        String type = format.get(FIELD_TYPE).asText();
        ObjectNode normalized = JSON.createObjectNode();
        if (TYPE_TEXT.equals(type)) {
            normalized.put(FIELD_TYPE, TYPE_TEXT);
            return normalized;
        }
        if (TYPE_GRAMMAR.equals(type)) {
            JsonNode grammar = format.get(TYPE_GRAMMAR);
            if (grammar == null || !grammar.isObject()) return null;
            String syntax = normalizeCustomToolGrammarSyntax(grammar.get(FIELD_SYNTAX));
            if (syntax == null || isBlankText(grammar.get(FIELD_DEFINITION))) return null;
            normalized.put(FIELD_TYPE, TYPE_GRAMMAR);
            normalized.put(FIELD_SYNTAX, syntax);
            normalized.put(FIELD_DEFINITION, grammar.get(FIELD_DEFINITION).asText());
            return normalized;
        }
        return null;
    }

    private static String normalizeCustomToolGrammarSyntax(JsonNode syntax) {
        if (syntax == null || !syntax.isTextual()) return null;
        return OpenAiChatCompletionsBodyPolicy.normalizeCustomToolGrammarSyntax(syntax.asText());
    }

    /**
     * 检测 base64 data URI 是否为空载荷。
     */
    private static boolean isEmptyBase64DataURI(String url) {
        return OpenAiResponsesBodyPolicy.isEmptyBase64DataUri(url);
    }

    private static boolean hasChatFilePayload(JsonNode file) {
        return !isBlankText(file.get(FIELD_FILE_DATA)) || !isBlankText(file.get(FIELD_FILE_ID));
    }

    private static boolean hasChatAudioPayload(JsonNode inputAudio) {
        return !isBlankText(inputAudio.get(FIELD_DATA)) && !isBlankText(inputAudio.get(FIELD_FORMAT));
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

    private static void copyNormalizedServiceTierIfExists(JsonNode src, ObjectNode dst) {
        if (!src.has(FIELD_SERVICE_TIER) || !src.get(FIELD_SERVICE_TIER).isTextual()) {
            return;
        }
        String normalized = OpenAiResponsesBodyPolicy.normalizeServiceTier(src.get(FIELD_SERVICE_TIER).asText());
        if (!normalized.isBlank()) {
            dst.put(FIELD_SERVICE_TIER, normalized);
        }
    }

    private static String extractChatReasoningEffort(JsonNode src) {
        JsonNode nested = src.path(FIELD_REASONING).get(FIELD_EFFORT);
        String normalized = normalizeChatReasoningEffort(nested);
        if (normalized != null) {
            return normalized;
        }
        return normalizeChatReasoningEffort(src.get(FIELD_REASONING_EFFORT));
    }

    private static String normalizeChatReasoningEffort(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        return OpenAiChatCompletionsBodyPolicy.normalizeReasoningEffort(node.asText());
    }

    private static JsonNode normalizeToolParameters(JsonNode parameters) {
        if (parameters != null && parameters.isObject()) {
            return parameters;
        }
        ObjectNode schema = JSON.createObjectNode();
        schema.put(FIELD_TYPE, TYPE_OBJECT);
        schema.set(FIELD_PROPERTIES, JSON.createObjectNode());
        return schema;
    }

    private static boolean isBlankText(JsonNode node) {
        return node == null || !node.isTextual() || node.asText().isBlank();
    }

    private static String textOrDefault(JsonNode node, String defaultValue) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : defaultValue;
    }

    // ========================
    // 通用工具方法
    // ========================

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
        return OpenAiChatCompletionsBodyPolicy.normalizeSearchContextSize(node.asText());
    }

    private static String normalizeAllowedToolsMode(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        return isSupportedToolChoiceMode(node.asText()) ? node.asText() : null;
    }

    private static String normalizeImageDetail(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        return OpenAiChatCompletionsBodyPolicy.normalizeImageDetail(node.asText());
    }

    private static void appendEvent(List<String> output, String event, String data) {
        output.add(OpenAiResponsesSsePolicy.EVENT_LINE_PREFIX + event);
        output.add(OpenAiResponsesSsePolicy.DATA_LINE_PREFIX + data);
        output.add(OpenAiResponsesSsePolicy.FRAME_SEPARATOR_LINE);
    }

}
