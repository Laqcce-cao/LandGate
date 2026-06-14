package com.landgate.trigger.gateway.converter.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.trigger.gateway.converter.StreamTranslator;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.AnthropicMessagesSsePolicy;
import com.landgate.types.gateway.AnthropicThinkingPolicy;
import com.landgate.types.gateway.GatewayProtocolIrPolicy;
import com.landgate.types.gateway.GatewayToolCallIdPolicy;
import com.landgate.types.gateway.GatewayWebSearchToolPolicy;
import com.landgate.types.gateway.OpenAiChatCompletionsBodyPolicy;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;
import com.landgate.types.gateway.OpenAiResponsesJsonPolicy;
import com.landgate.types.gateway.OpenAiResponsesSsePolicy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * OpenAI Responses API（IR）→ Anthropic Messages API 转换器。
 * <p>
 * 负责请求反向转换、非流式响应反向转换、流式 SSE 反向翻译。
 * 由 {@link AnthropicConverter} 门面委托调用，不作为独立 Spring Bean。
 *
 * <p>参照：sub2api {@code responses_to_anthropic.go} + {@code responses_to_anthropic_request.go}
 */
@Slf4j
public class ResponsesToAnthropicConverter {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ========================
    // 请求反向转换：Responses IR → Anthropic
    // ========================

    /**
     * 将 Responses API 请求（IR）转换为 Anthropic Messages 请求。
     *
     * @param ir Responses 格式的 JsonNode
     * @return Anthropic 请求 JSON 字符串
     */
    public String requestFromIR(JsonNode ir) {
        try {
            ObjectNode dst = JSON.createObjectNode();

            copyTextIfExists(ir, dst, OpenAiResponsesBodyPolicy.FIELD_MODEL);
            copyBooleanIfExists(ir, dst, OpenAiResponsesBodyPolicy.FIELD_STREAM);
            copyNumberIfExists(ir, dst, OpenAiResponsesBodyPolicy.FIELD_TEMPERATURE);
            copyNumberIfExists(ir, dst, OpenAiResponsesBodyPolicy.FIELD_TOP_P);

            // Anthropic max_tokens is required; if Responses omits it or sends an invalid value,
            // use the gateway default instead of forwarding a malformed limit.
            dst.put(AnthropicMessagesBodyPolicy.FIELD_MAX_TOKENS,
                    isPositiveInt(ir.get(OpenAiResponsesBodyPolicy.FIELD_MAX_OUTPUT_TOKENS))
                    ? ir.get(OpenAiResponsesBodyPolicy.FIELD_MAX_OUTPUT_TOKENS).asInt()
                    : AnthropicMessagesBodyPolicy.DEFAULT_MAX_TOKENS);

            JsonNode stopSequences = normalizeStopSequences(ir.get(GatewayProtocolIrPolicy.FIELD_STOP_SEQUENCES));
            if (stopSequences != null) {
                dst.set(AnthropicMessagesBodyPolicy.FIELD_STOP_SEQUENCES, stopSequences);
            }

            // input[] → system + messages[]
            String systemText = null;
            if (ir.has(OpenAiResponsesBodyPolicy.FIELD_INSTRUCTIONS)
                    && !isBlankText(ir.get(OpenAiResponsesBodyPolicy.FIELD_INSTRUCTIONS))) {
                systemText = appendSystemText(systemText,
                        ir.get(OpenAiResponsesBodyPolicy.FIELD_INSTRUCTIONS).asText());
            }
            List<JsonNode> inputItems = new ArrayList<>();
            if (ir.has(OpenAiResponsesBodyPolicy.FIELD_INPUT)) {
                JsonNode inputNode = ir.get(OpenAiResponsesBodyPolicy.FIELD_INPUT);
                if (inputNode.isArray()) {
                    for (JsonNode item : inputNode) {
                        String role = textOrDefault(item.get(OpenAiResponsesBodyPolicy.FIELD_ROLE), null);
                        String itemType = textOrDefault(item.get(OpenAiResponsesBodyPolicy.FIELD_TYPE), null);

                        // system/developer role → 提取到 Anthropic 顶层 system
                        if (OpenAiChatCompletionsBodyPolicy.ROLE_SYSTEM.equals(role)
                                || OpenAiResponsesBodyPolicy.ROLE_DEVELOPER.equals(role)) {
                            String text = extractTextFromContent(item.get(OpenAiResponsesBodyPolicy.FIELD_CONTENT));
                            if (text != null && !text.isEmpty()) {
                                systemText = appendSystemText(systemText, text);
                            }
                            continue;
                        }

                        // function_call → assistant message with tool_use
                        if (OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL.equals(itemType)) {
                            JsonNode assistantMsg = convertFunctionCallToAssistantMsg(item);
                            if (assistantMsg != null) {
                                inputItems.add(assistantMsg);
                            }
                            continue;
                        }

                        // function_call_output → user message with tool_result
                        if (OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL_OUTPUT.equals(itemType)) {
                            JsonNode toolResultMsg = convertFunctionCallOutputToUserMsg(item);
                            if (toolResultMsg != null) {
                                inputItems.add(toolResultMsg);
                            }
                            continue;
                        }

                        if (OpenAiResponsesJsonPolicy.TYPE_REASONING.equals(itemType)) {
                            inputItems.add(convertReasoningToAssistantMsg(item));
                            continue;
                        }

                        // 普通 message item
                        inputItems.add(item);
                    }
                } else if (inputNode.isTextual()) {
                    // 字符串 input（如 "hello"）→ 转为单条 user message
                    ObjectNode userMsg = JSON.createObjectNode();
                    userMsg.put(AnthropicMessagesBodyPolicy.FIELD_ROLE, AnthropicMessagesBodyPolicy.ROLE_USER);
                    userMsg.put(AnthropicMessagesBodyPolicy.FIELD_CONTENT, inputNode.asText());
                    inputItems.add(userMsg);
                }
            }

            // messages 数组构建
            ArrayNode messages = JSON.createArrayNode();
            for (JsonNode item : inputItems) {
                String role = textOrDefault(item.get(OpenAiResponsesBodyPolicy.FIELD_ROLE), null);
                if (role == null) continue;

                switch (role) {
                    case AnthropicMessagesBodyPolicy.ROLE_USER -> {
                        JsonNode content = convertUserContentToAnthropic(item.get(OpenAiResponsesBodyPolicy.FIELD_CONTENT));
                        if (content != null) {
                            ObjectNode msg = JSON.createObjectNode();
                            msg.put(AnthropicMessagesBodyPolicy.FIELD_ROLE, AnthropicMessagesBodyPolicy.ROLE_USER);
                            msg.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, content);
                            messages.add(msg);
                        }
                    }
                    case AnthropicMessagesBodyPolicy.ROLE_ASSISTANT -> {
                        JsonNode content = convertAssistantContentToAnthropic(item.get(OpenAiResponsesBodyPolicy.FIELD_CONTENT));
                        if (content != null) {
                            ObjectNode msg = JSON.createObjectNode();
                            msg.put(AnthropicMessagesBodyPolicy.FIELD_ROLE, AnthropicMessagesBodyPolicy.ROLE_ASSISTANT);
                            msg.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, content);
                            messages.add(msg);
                        }
                    }
                    default -> {
                        // 未知 role → user
                        JsonNode content = convertUserContentToAnthropic(item.get(OpenAiResponsesBodyPolicy.FIELD_CONTENT));
                        if (content != null) {
                            ObjectNode msg = JSON.createObjectNode();
                            msg.put(AnthropicMessagesBodyPolicy.FIELD_ROLE, AnthropicMessagesBodyPolicy.ROLE_USER);
                            msg.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, content);
                            messages.add(msg);
                        }
                    }
                }
            }

            // 合并连续同角色消息（Anthropic 要求 user/assistant 交替）
            messages = mergeConsecutiveMessages(messages);

            boolean bridgePromptCache = hasText(ir, OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_KEY);

            // system
            if (systemText != null && !systemText.isEmpty()) {
                if (bridgePromptCache) {
                    ArrayNode systemBlocks = JSON.createArrayNode();
                    ObjectNode systemBlock = JSON.createObjectNode();
                    systemBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
                    systemBlock.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, systemText);
                    systemBlock.set(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL, ephemeralCacheControl());
                    systemBlocks.add(systemBlock);
                    dst.set(AnthropicMessagesBodyPolicy.FIELD_SYSTEM, systemBlocks);
                } else {
                    dst.put(AnthropicMessagesBodyPolicy.FIELD_SYSTEM, systemText);
                }
            } else if (bridgePromptCache) {
                attachCacheControlToFirstUserTextBlock(messages);
            }
            dst.set(AnthropicMessagesBodyPolicy.FIELD_MESSAGES, messages);

            // --- tools ---
            if (ir.has(OpenAiResponsesBodyPolicy.FIELD_TOOLS) && ir.get(OpenAiResponsesBodyPolicy.FIELD_TOOLS).isArray()) {
                ArrayNode anthropicTools = JSON.createArrayNode();
                for (JsonNode tool : ir.get(OpenAiResponsesBodyPolicy.FIELD_TOOLS)) {
                    JsonNode convertedTool = convertResponsesToolToAnthropic(tool);
                    if (convertedTool != null) {
                        anthropicTools.add(convertedTool);
                    }
                }
                if (anthropicTools.size() > 0) {
                    dst.set(AnthropicMessagesBodyPolicy.FIELD_TOOLS, anthropicTools);
                }
            }

            // --- tool_choice ---
            if (ir.has(OpenAiResponsesBodyPolicy.FIELD_TOOL_CHOICE)) {
                JsonNode toolChoice = convertResponsesToolChoiceToAnthropic(
                        ir.get(OpenAiResponsesBodyPolicy.FIELD_TOOL_CHOICE));
                if (toolChoice != null) {
                    dst.set(AnthropicMessagesBodyPolicy.FIELD_TOOL_CHOICE, toolChoice);
                }
            }

            // --- reasoning.effort → output_config.effort + thinking ---
            if (ir.has(OpenAiResponsesBodyPolicy.FIELD_REASONING)
                    && ir.get(OpenAiResponsesBodyPolicy.FIELD_REASONING).has(OpenAiResponsesBodyPolicy.FIELD_EFFORT)) {
                String anthropicEffort = mapResponsesEffortToAnthropic(ir.get(OpenAiResponsesBodyPolicy.FIELD_REASONING)
                        .get(OpenAiResponsesBodyPolicy.FIELD_EFFORT));
                if (anthropicEffort != null) {
                    ObjectNode outputConfig = JSON.createObjectNode();
                    outputConfig.put(OpenAiResponsesBodyPolicy.FIELD_EFFORT, anthropicEffort);
                    dst.set("output_config", outputConfig);
                }

                if (anthropicEffort != null && !"low".equals(anthropicEffort)) {
                    ObjectNode thinking = JSON.createObjectNode();
                    thinking.put(AnthropicMessagesBodyPolicy.FIELD_TYPE,
                            AnthropicThinkingPolicy.THINKING_MODE_ENABLED);
                    thinking.put(AnthropicThinkingPolicy.FIELD_BUDGET_TOKENS,
                            defaultThinkingBudget(anthropicEffort));
                    dst.set(AnthropicThinkingPolicy.FIELD_THINKING, thinking);
                }
            }

            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Responses→Anthropic requestFromIR error: {}", e.getMessage());
            return "{}";
        }
    }

    // ========================
    // 非流式响应反向转换：Responses IR → Anthropic
    // ========================

    /**
     * 将 Responses 非流式响应（IR）转换为 Anthropic 响应。
     */
    public String responseFromIR(JsonNode ir) {
        try {
            ObjectNode dst = JSON.createObjectNode();

            dst.put(AnthropicMessagesBodyPolicy.FIELD_ID,
                    textOrDefault(ir.get(OpenAiResponsesJsonPolicy.FIELD_ID),
                            AnthropicMessagesBodyPolicy.MESSAGE_ID_PREFIX
                                    + UUID.randomUUID().toString().replace("-", "")
                                    .substring(0, AnthropicMessagesBodyPolicy.MESSAGE_ID_RANDOM_LENGTH)));
            dst.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_MESSAGE);
            dst.put(AnthropicMessagesBodyPolicy.FIELD_ROLE, AnthropicMessagesBodyPolicy.ROLE_ASSISTANT);
            dst.put(AnthropicMessagesBodyPolicy.FIELD_MODEL,
                    textOrDefault(ir.get(OpenAiResponsesJsonPolicy.FIELD_MODEL), OpenAiResponsesJsonPolicy.DEFAULT_MODEL));

            // output[] → content[]
            ArrayNode content = JSON.createArrayNode();
            boolean hasToolUse = false;

            if (ir.has(OpenAiResponsesJsonPolicy.FIELD_OUTPUT)
                    && ir.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT).isArray()) {
                for (JsonNode item : ir.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT)) {
                    String itemType = textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), "");

                    switch (itemType) {
                        case OpenAiResponsesJsonPolicy.TYPE_REASONING -> {
                            addResponseReasoningBlocks(content, item);
                        }
                        case OpenAiResponsesJsonPolicy.TYPE_MESSAGE -> {
                            JsonNode msgContent = item.get(OpenAiResponsesJsonPolicy.FIELD_CONTENT);
                            if (msgContent != null && msgContent.isArray()) {
                                for (JsonNode part : msgContent) {
                                    String partType = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), "");
                                    String text = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_TEXT), "");
                                    String refusal = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_REFUSAL), "");
                                    if (OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT.equals(partType) && !text.isEmpty()) {
                                        ObjectNode textBlock = JSON.createObjectNode();
                                        textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE,
                                                AnthropicMessagesBodyPolicy.TYPE_TEXT);
                                        textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, text);
                                        content.add(textBlock);
                                    } else if (OpenAiResponsesJsonPolicy.TYPE_REFUSAL.equals(partType)
                                            && !refusal.isEmpty()) {
                                        ObjectNode textBlock = JSON.createObjectNode();
                                        textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE,
                                                AnthropicMessagesBodyPolicy.TYPE_TEXT);
                                        textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, refusal);
                                        content.add(textBlock);
                                    }
                                }
                            }
                        }
                        case OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL -> {
                            if (isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID))
                                    || isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) {
                                log.debug("Responses→Anthropic: output function_call missing call_id or name, ignored");
                                continue;
                            }
                            hasToolUse = true;
                            ObjectNode toolUse = JSON.createObjectNode();
                            toolUse.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TOOL_USE);
                            toolUse.put(AnthropicMessagesBodyPolicy.FIELD_ID,
                                    GatewayToolCallIdPolicy.fromResponsesCallIdForAnthropicResponse(
                                            item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID).asText()));
                            toolUse.put(AnthropicMessagesBodyPolicy.FIELD_NAME,
                                    item.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText());
                            String args = textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS),
                                    OpenAiResponsesJsonPolicy.EMPTY_JSON_OBJECT);
                            args = sanitizeAnthropicToolUseInput(
                                    item.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText(), args);
                            try {
                                toolUse.set(AnthropicMessagesBodyPolicy.FIELD_INPUT, JSON.readTree(args));
                            } catch (Exception e) {
                                toolUse.put(AnthropicMessagesBodyPolicy.FIELD_INPUT, args);
                            }
                            content.add(toolUse);
                        }
                        case OpenAiResponsesJsonPolicy.TYPE_WEB_SEARCH_CALL -> {
                            addWebSearchBlocks(content, item);
                        }
                        default ->
                            log.debug("Responses→Anthropic: unknown output type '{}'", itemType);
                    }
                }
            }

            if (content.size() == 0) {
                ObjectNode emptyText = JSON.createObjectNode();
                emptyText.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
                emptyText.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, "");
                content.add(emptyText);
            }
            dst.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, content);

            // stop_reason
            String status = textOrDefault(ir.get(OpenAiResponsesJsonPolicy.FIELD_STATUS),
                    OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
            String stopReason = mapResponsesStatusToAnthropicStopReason(status, ir, hasToolUse);
            dst.put(AnthropicMessagesBodyPolicy.FIELD_STOP_REASON, stopReason);

            // usage
            if (ir.has(OpenAiResponsesJsonPolicy.FIELD_USAGE)) {
                JsonNode usage = ir.get(OpenAiResponsesJsonPolicy.FIELD_USAGE);
                int inputTokens = nonNegativeIntOrZero(usage.get(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS));
                int outputTokens = nonNegativeIntOrZero(usage.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_TOKENS));
                int cachedTokens = nonNegativeIntOrZero(usage.path(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS)
                        .get(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS));

                // Anthropic input_tokens 不包含缓存 token（减去 cached_tokens，最小为 0）
                int anthropicInputTokens = Math.max(inputTokens - cachedTokens, 0);
                ObjectNode anthropicUsage = JSON.createObjectNode();
                anthropicUsage.put(AnthropicMessagesBodyPolicy.FIELD_INPUT_TOKENS, anthropicInputTokens);
                anthropicUsage.put(AnthropicMessagesBodyPolicy.FIELD_OUTPUT_TOKENS, outputTokens);
                if (cachedTokens > 0) {
                    anthropicUsage.put(AnthropicMessagesBodyPolicy.FIELD_CACHE_READ_INPUT_TOKENS, cachedTokens);
                }
                dst.set(AnthropicMessagesBodyPolicy.FIELD_USAGE, anthropicUsage);
            }

            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Responses→Anthropic responseFromIR error: {}", e.getMessage());
            return "{}";
        }
    }

    // ========================
    // 流式 SSE 翻译：Responses SSE（IR）→ Anthropic SSE
    // ========================

    /**
     * 创建 Responses SSE → Anthropic SSE 流式翻译器。
     */
    public StreamTranslator createStreamFromIR(String model) {
        return new IRToAnthropicStreamTranslator(model);
    }

    // ========================
    // 流式翻译器内部类
    // ========================

    /**
     * Responses SSE 事件 → Anthropic SSE 事件。
     * <p>
     * 状态机跟踪当前 block 类型、content_block_index，
     * 将 Responses 的 response.output_text.delta 等事件翻译为 Anthropic SSE 事件。
     */
    static class IRToAnthropicStreamTranslator implements StreamTranslator {

        private boolean done = false;
        private boolean messageStartSent = false;
        private String messageId;
        private String model;
        // 起始 -1：每个新块开启前都会先做 ++，首块发出的 index 就是 0（符合 Anthropic SSE 规范）。
        // Claude CLI 严格校验首块 index 必须为 0，否则会拒绝整个流。
        private int contentBlockIndex = -1;
        private boolean contentBlockOpen = false;
        private String currentBlockType = null; // "text", Anthropic thinking, or "tool_use"
        private String currentToolName = null;
        private boolean currentToolHadDelta = false;
        private String currentToolArgs = "";
        private boolean hasToolCall = false;
        private final Map<Integer, Integer> outputIndexToBlockIdx = new HashMap<>();
        private final Set<Integer> textDeltasSeen = new HashSet<>();
        private final Set<Integer> refusalDeltasSeen = new HashSet<>();
        private final Set<Integer> toolArgumentDeltasSeen = new HashSet<>();
        private final Set<Integer> reasoningDeltasSeen = new HashSet<>();

        private int inputTokens = 0;
        private int outputTokens = 0;
        private int cacheReadInputTokens = 0;

        IRToAnthropicStreamTranslator(String model) {
            this.model = model != null ? model : "unknown";
            this.messageId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }

        @Override
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (done || line == null || line.isBlank()) return output;
            String json = OpenAiResponsesSsePolicy.extractDataPayload(line);
            if (json == null || OpenAiResponsesSsePolicy.isDoneSentinel(json)) return output;
            try {
                JsonNode root = JSON.readTree(json);
                String type = textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), null);
                if (type == null) return output;

                switch (type) {
                    case OpenAiResponsesSsePolicy.EVENT_RESPONSE_CREATED -> {
                        if (!messageStartSent) {
                            if (root.has(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)) {
                                JsonNode resp = root.get(OpenAiResponsesJsonPolicy.FIELD_RESPONSE);
                                if (!isBlankText(resp.get(OpenAiResponsesJsonPolicy.FIELD_MODEL))) {
                                    model = resp.get(OpenAiResponsesJsonPolicy.FIELD_MODEL).asText();
                                }
                            }
                            appendEvent(output, AnthropicMessagesSsePolicy.EVENT_MESSAGE_START,
                                    messageStartData(messageId, model));
                            messageStartSent = true;
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_OUTPUT_ITEM_ADDED -> {
                        String itemType = root.has(OpenAiResponsesJsonPolicy.FIELD_ITEM)
                                ? textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_ITEM)
                                        .get(OpenAiResponsesJsonPolicy.FIELD_TYPE), "")
                                : "";
                        int outputIndex = nonNegativeIntOrZero(root.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX));

                        closeCurrentBlock(output);

                        switch (itemType) {
                            case OpenAiResponsesJsonPolicy.TYPE_MESSAGE -> { /* 不产出事件，由 text delta 隐式处理 */ }
                            case OpenAiResponsesJsonPolicy.TYPE_REASONING -> {
                                contentBlockIndex++;
                                outputIndexToBlockIdx.put(outputIndex, contentBlockIndex);
                                currentBlockType = AnthropicThinkingPolicy.TYPE_THINKING;
                                contentBlockOpen = true;
                                appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_START,
                                        thinkingBlockStartData(contentBlockIndex));
                            }
                            case OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL -> {
                                JsonNode item = root.get(OpenAiResponsesJsonPolicy.FIELD_ITEM);
                                if (isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID))
                                        || isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) {
                                    log.debug("IR→Anthropic stream: function_call missing call_id or name, ignored");
                                    break;
                                }
                                contentBlockIndex++;
                                outputIndexToBlockIdx.put(outputIndex, contentBlockIndex);
                                currentBlockType = AnthropicMessagesBodyPolicy.TYPE_TOOL_USE;
                                contentBlockOpen = true;
                                currentToolName = item.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText();
                                String callId = GatewayToolCallIdPolicy.fromResponsesCallIdForAnthropicResponse(
                                        item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID).asText());
                                currentToolHadDelta = false;
                                currentToolArgs = "";
                                hasToolCall = true;

                                appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_START,
                                        toolUseBlockStartData(contentBlockIndex, callId, currentToolName));
                            }
                            case OpenAiResponsesJsonPolicy.TYPE_WEB_SEARCH_CALL -> {
                                // 暂不处理
                                log.debug("IR→Anthropic stream: web_search_call not yet implemented");
                            }
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_OUTPUT_TEXT_DELTA -> {
                        String text = textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_DELTA), "");
                        if (!text.isEmpty()) {
                            textDeltasSeen.add(contentKey(root));
                            // 如果没有打开的 text block，自动开始一个
                            if (!contentBlockOpen || !AnthropicMessagesBodyPolicy.TYPE_TEXT.equals(currentBlockType)) {
                                closeCurrentBlock(output);
                                contentBlockIndex++;
                                currentBlockType = AnthropicMessagesBodyPolicy.TYPE_TEXT;
                                contentBlockOpen = true;
                                appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_START,
                                        textBlockStartData(contentBlockIndex));
                            }
                            appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_DELTA,
                                    textDeltaData(contentBlockIndex, text));
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_OUTPUT_TEXT_DONE -> {
                        String text = textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_TEXT), "");
                        int key = contentKey(root);
                        if (!text.isEmpty() && !textDeltasSeen.contains(key)) {
                            if (!contentBlockOpen || !AnthropicMessagesBodyPolicy.TYPE_TEXT.equals(currentBlockType)) {
                                closeCurrentBlock(output);
                                contentBlockIndex++;
                                currentBlockType = AnthropicMessagesBodyPolicy.TYPE_TEXT;
                                contentBlockOpen = true;
                                appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_START,
                                        textBlockStartData(contentBlockIndex));
                            }
                            appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_DELTA,
                                    textDeltaData(contentBlockIndex, text));
                            textDeltasSeen.add(key);
                        }
                        closeCurrentBlock(output);
                    }
                    case OpenAiResponsesSsePolicy.EVENT_CONTENT_PART_DONE -> {
                        JsonNode part = root.path(OpenAiResponsesJsonPolicy.FIELD_PART);
                        String partType = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), "");
                        int key = contentKey(root);
                        if (OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT.equals(partType) && !textDeltasSeen.contains(key)) {
                            String text = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_TEXT), "");
                            if (!text.isEmpty()) {
                                emitTextBlockDelta(output, text);
                                textDeltasSeen.add(key);
                            }
                        } else if (OpenAiResponsesJsonPolicy.TYPE_REFUSAL.equals(partType) && !refusalDeltasSeen.contains(key)) {
                            String refusal = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_REFUSAL), "");
                            if (!refusal.isEmpty()) {
                                emitTextBlockDelta(output, refusal);
                                refusalDeltasSeen.add(key);
                            }
                        }
                        closeCurrentBlock(output);
                    }
                    case OpenAiResponsesSsePolicy.EVENT_REFUSAL_DELTA -> {
                        String delta = textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_DELTA), "");
                        if (!delta.isEmpty()) {
                            refusalDeltasSeen.add(contentKey(root));
                            emitTextBlockDelta(output, delta);
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_REFUSAL_DONE -> {
                        String refusal = textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_REFUSAL), "");
                        int key = contentKey(root);
                        if (!refusal.isEmpty() && !refusalDeltasSeen.contains(key)) {
                            emitTextBlockDelta(output, refusal);
                            refusalDeltasSeen.add(key);
                        }
                        closeCurrentBlock(output);
                    }
                    case OpenAiResponsesSsePolicy.EVENT_FUNCTION_CALL_ARGUMENTS_DELTA -> {
                        String delta = textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_DELTA), "");
                        if (!delta.isEmpty()) {
                            int outputIdx = nonNegativeIntOrZero(root.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX));
                            if (!outputIndexToBlockIdx.containsKey(outputIdx)) {
                                log.debug("IR→Anthropic stream: function_call arguments delta without valid tool block ignored");
                                return output;
                            }
                            toolArgumentDeltasSeen.add(outputIdx);
                            if (AnthropicMessagesBodyPolicy.TOOL_NAME_READ.equals(currentToolName)) {
                                // Read 工具：缓冲 delta，不实时输出
                                currentToolArgs += delta;
                            } else {
                                currentToolHadDelta = true;
                                int idx = findBlockIndex(root);
                                appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_DELTA,
                                        inputJsonDeltaData(idx, delta));
                            }
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_FUNCTION_CALL_ARGUMENTS_DONE -> {
                        int outputIdx = nonNegativeIntOrZero(root.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX));
                        if (!outputIndexToBlockIdx.containsKey(outputIdx)) {
                            log.debug("IR→Anthropic stream: function_call arguments done without valid tool block ignored");
                            return output;
                        }
                        if (!currentToolHadDelta) {
                            String args = textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS), currentToolArgs);
                            if (AnthropicMessagesBodyPolicy.TOOL_NAME_READ.equals(currentToolName)) {
                                args = sanitizeAnthropicToolUseInput(currentToolName, args);
                            }
                            if (!args.isEmpty() && !"{}".equals(args)) {
                                int idx = findBlockIndex(root);
                                appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_DELTA,
                                        inputJsonDeltaData(idx, args));
                                currentToolHadDelta = true;
                            }
                        }
                        closeCurrentBlock(output);
                    }
                    case OpenAiResponsesSsePolicy.EVENT_REASONING_SUMMARY_TEXT_DELTA,
                            OpenAiResponsesSsePolicy.EVENT_REASONING_TEXT_DELTA -> {
                        String delta = textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_DELTA), "");
                        if (!delta.isEmpty()) {
                            int outputIdx = nonNegativeIntOrZero(root.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX));
                            reasoningDeltasSeen.add(outputIdx);
                            int idx = findBlockIndex(root);
                            appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_DELTA,
                                    thinkingDeltaData(idx, delta));
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_REASONING_SUMMARY_TEXT_DONE,
                            OpenAiResponsesSsePolicy.EVENT_REASONING_TEXT_DONE -> {
                        closeCurrentBlock(output);
                    }
                    case OpenAiResponsesSsePolicy.EVENT_OUTPUT_ITEM_DONE -> {
                        String itemType = root.has(OpenAiResponsesJsonPolicy.FIELD_ITEM)
                                && root.get(OpenAiResponsesJsonPolicy.FIELD_ITEM).has(OpenAiResponsesJsonPolicy.FIELD_TYPE)
                                ? root.get(OpenAiResponsesJsonPolicy.FIELD_ITEM)
                                        .get(OpenAiResponsesJsonPolicy.FIELD_TYPE).asText()
                                : "";
                        JsonNode item = root.get(OpenAiResponsesJsonPolicy.FIELD_ITEM);
                        if (OpenAiResponsesJsonPolicy.TYPE_WEB_SEARCH_CALL.equals(itemType)
                                && OpenAiResponsesJsonPolicy.STATUS_COMPLETED.equals(item.has(OpenAiResponsesJsonPolicy.FIELD_STATUS)
                                    ? item.get(OpenAiResponsesJsonPolicy.FIELD_STATUS).asText() : "")) {
                            // web_search_call completed → server_tool_use + web_search_tool_result
                            closeCurrentBlock(output);
                            contentBlockIndex++;
                            String srvId = AnthropicMessagesBodyPolicy.WEB_SEARCH_SERVER_TOOL_ID_PREFIX
                                    + (item.has(OpenAiResponsesJsonPolicy.FIELD_ID)
                                    ? item.get(OpenAiResponsesJsonPolicy.FIELD_ID).asText() : UUID.randomUUID());
                            String query = "";
                            JsonNode action = item.get(OpenAiResponsesJsonPolicy.FIELD_ACTION);
                            if (action != null && action.isObject()
                                    && !isBlankText(action.get(OpenAiResponsesJsonPolicy.FIELD_QUERY))) {
                                query = action.get(OpenAiResponsesJsonPolicy.FIELD_QUERY).asText();
                            }
                            appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_START,
                                    serverToolUseBlockStartData(contentBlockIndex, srvId, query));
                            appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_STOP,
                                    contentBlockStopData(contentBlockIndex));
                            contentBlockIndex++;
                            appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_START,
                                    webSearchToolResultBlockStartData(contentBlockIndex, srvId));
                            appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_STOP,
                                    contentBlockStopData(contentBlockIndex));
                        } else if (root.has(OpenAiResponsesJsonPolicy.FIELD_ITEM)) {
                            int outputIndex = nonNegativeIntOrZero(root.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX));
                            emitFinalOutputItem(output, outputIndex, item);
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_RESPONSE_COMPLETED,
                            OpenAiResponsesSsePolicy.EVENT_RESPONSE_DONE,
                            OpenAiResponsesSsePolicy.EVENT_RESPONSE_INCOMPLETE,
                            OpenAiResponsesSsePolicy.EVENT_RESPONSE_FAILED -> {
                        if (root.has(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                                && root.get(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                                        .has(OpenAiResponsesJsonPolicy.FIELD_OUTPUT)
                                && root.get(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                                        .get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT).isArray()) {
                            JsonNode responseOutput = root.get(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                                    .get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT);
                            for (int i = 0; i < responseOutput.size(); i++) {
                                emitFinalOutputItem(output, i, responseOutput.get(i));
                            }
                        }
                        closeCurrentBlock(output);

                        // usage：优先从顶层 usage 读取，fallback 到 response.usage
                        int usageInput = 0, usageOutput = 0, usageCached = 0;
                        if (root.has(OpenAiResponsesJsonPolicy.FIELD_USAGE)) {
                            JsonNode u = root.get(OpenAiResponsesJsonPolicy.FIELD_USAGE);
                            if (isNonNegativeInt(u.get(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS))) {
                                usageInput = u.get(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS).asInt();
                            }
                            if (isNonNegativeInt(u.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_TOKENS))) {
                                usageOutput = u.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_TOKENS).asInt();
                            }
                            if (u.has(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS)
                                    && isNonNegativeInt(u.get(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS)
                                            .get(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS))) {
                                usageCached = u.get(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS)
                                        .get(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS).asInt();
                            }
                        } else if (root.has(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                                && root.get(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                                        .has(OpenAiResponsesJsonPolicy.FIELD_USAGE)) {
                            JsonNode u = root.get(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                                    .get(OpenAiResponsesJsonPolicy.FIELD_USAGE);
                            if (isNonNegativeInt(u.get(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS))) {
                                usageInput = u.get(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS).asInt();
                            }
                            if (isNonNegativeInt(u.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_TOKENS))) {
                                usageOutput = u.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_TOKENS).asInt();
                            }
                            if (u.has(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS)
                                    && isNonNegativeInt(u.get(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS)
                                            .get(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS))) {
                                usageCached = u.get(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS)
                                        .get(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS).asInt();
                            }
                        }

                        inputTokens = Math.max(usageInput - usageCached, 0);
                        outputTokens = usageOutput;
                        cacheReadInputTokens = usageCached;

                        // stop_reason
                        String stopReason;
                        if (root.has(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)) {
                            JsonNode resp = root.get(OpenAiResponsesJsonPolicy.FIELD_RESPONSE);
                            String respStatus = textOrDefault(resp.get(OpenAiResponsesJsonPolicy.FIELD_STATUS), "");
                            if (OpenAiResponsesJsonPolicy.STATUS_INCOMPLETE.equals(respStatus)) {
                                String incompleteReason = textOrDefault(resp
                                        .path(OpenAiResponsesJsonPolicy.FIELD_INCOMPLETE_DETAILS)
                                        .get(OpenAiResponsesJsonPolicy.FIELD_REASON), "");
                                stopReason = OpenAiResponsesJsonPolicy.DEFAULT_INCOMPLETE_REASON.equals(incompleteReason)
                                        ? AnthropicMessagesBodyPolicy.STOP_REASON_MAX_TOKENS
                                        : AnthropicMessagesBodyPolicy.STOP_REASON_END_TURN;
                            } else {
                                stopReason = hasToolCall
                                        ? AnthropicMessagesBodyPolicy.STOP_REASON_TOOL_USE
                                        : AnthropicMessagesBodyPolicy.STOP_REASON_END_TURN;
                            }
                        } else {
                            stopReason = hasToolCall
                                    ? AnthropicMessagesBodyPolicy.STOP_REASON_TOOL_USE
                                    : AnthropicMessagesBodyPolicy.STOP_REASON_END_TURN;
                        }

                        appendEvent(output, AnthropicMessagesSsePolicy.EVENT_MESSAGE_DELTA,
                                messageDeltaData(stopReason, outputTokens, cacheReadInputTokens));
                        appendEvent(output, AnthropicMessagesSsePolicy.EVENT_MESSAGE_STOP, messageStopData());
                        done = true;
                    }
                }
            } catch (Exception e) {
                log.debug("IR→Anthropic SSE error: {}", e.getMessage());
            }
            return output;
        }

        private void emitFinalOutputItem(List<String> output, int outputIndex, JsonNode item) {
            String itemType = textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), "");
            if (OpenAiResponsesJsonPolicy.TYPE_MESSAGE.equals(itemType)
                    && item.has(OpenAiResponsesJsonPolicy.FIELD_CONTENT)
                    && item.get(OpenAiResponsesJsonPolicy.FIELD_CONTENT).isArray()) {
                for (int contentIndex = 0; contentIndex < item.get(OpenAiResponsesJsonPolicy.FIELD_CONTENT).size(); contentIndex++) {
                    JsonNode part = item.get(OpenAiResponsesJsonPolicy.FIELD_CONTENT).get(contentIndex);
                    String partType = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), "");
                    int key = outputIndex * 10_000 + contentIndex;
                    if (OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT.equals(partType) && !textDeltasSeen.contains(key)) {
                        String text = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_TEXT), "");
                        if (!text.isEmpty()) {
                            emitTextBlockDelta(output, text);
                            textDeltasSeen.add(key);
                        }
                    } else if (OpenAiResponsesJsonPolicy.TYPE_REFUSAL.equals(partType) && !refusalDeltasSeen.contains(key)) {
                        String refusal = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_REFUSAL), "");
                        if (!refusal.isEmpty()) {
                            emitTextBlockDelta(output, refusal);
                            refusalDeltasSeen.add(key);
                        }
                    }
                }
            } else if (OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL.equals(itemType)) {
                if (isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID))
                        || isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) {
                    log.debug("IR→Anthropic stream: final function_call missing call_id or name, ignored");
                    return;
                }
                hasToolCall = true;
                Integer blockIndex = outputIndexToBlockIdx.get(outputIndex);
                if (blockIndex == null) {
                    closeCurrentBlock(output);
                    contentBlockIndex++;
                    blockIndex = contentBlockIndex;
                    outputIndexToBlockIdx.put(outputIndex, blockIndex);
                    currentBlockType = AnthropicMessagesBodyPolicy.TYPE_TOOL_USE;
                    contentBlockOpen = true;
                    currentToolName = item.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText();
                    String callId = GatewayToolCallIdPolicy.fromResponsesCallIdForAnthropicResponse(
                            item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID).asText());
                    appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_START,
                            toolUseBlockStartData(blockIndex, callId, currentToolName));
                }
                String arguments = textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS), "");
                if (!arguments.isEmpty() && !toolArgumentDeltasSeen.contains(outputIndex)) {
                    if (currentToolName != null) {
                        arguments = sanitizeAnthropicToolUseInput(currentToolName, arguments);
                    }
                    if (!arguments.isEmpty() && !"{}".equals(arguments)) {
                        appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_DELTA,
                                inputJsonDeltaData(blockIndex, arguments));
                    }
                    toolArgumentDeltasSeen.add(outputIndex);
                }
                closeCurrentBlock(output);
            } else if (OpenAiResponsesJsonPolicy.TYPE_REASONING.equals(itemType) && !reasoningDeltasSeen.contains(outputIndex)
                    && (item.has(OpenAiResponsesJsonPolicy.FIELD_CONTENT) || item.has(OpenAiResponsesJsonPolicy.FIELD_SUMMARY))) {
                String thinking = extractReasoningText(item);
                if (!thinking.isEmpty()) {
                    closeCurrentBlock(output);
                    contentBlockIndex++;
                    outputIndexToBlockIdx.put(outputIndex, contentBlockIndex);
                    currentBlockType = AnthropicThinkingPolicy.TYPE_THINKING;
                    contentBlockOpen = true;
                    appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_START,
                            thinkingBlockStartData(contentBlockIndex));
                    appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_DELTA,
                            thinkingDeltaData(contentBlockIndex, thinking));
                    reasoningDeltasSeen.add(outputIndex);
                    closeCurrentBlock(output);
                }
            }
        }

        private void closeCurrentBlock(List<String> output) {
            if (contentBlockOpen) {
                appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_STOP,
                        contentBlockStopData(contentBlockIndex));
                contentBlockOpen = false;
                currentBlockType = null;
            }
        }

        private int findBlockIndex(JsonNode root) {
            if (isNonNegativeInt(root.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX))) {
                int oi = root.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX).asInt();
                return outputIndexToBlockIdx.getOrDefault(oi, contentBlockIndex);
            }
            return contentBlockIndex;
        }

        private void emitTextBlockDelta(List<String> output, String text) {
            if (!contentBlockOpen || !AnthropicMessagesBodyPolicy.TYPE_TEXT.equals(currentBlockType)) {
                closeCurrentBlock(output);
                contentBlockIndex++;
                currentBlockType = AnthropicMessagesBodyPolicy.TYPE_TEXT;
                contentBlockOpen = true;
                appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_START,
                        textBlockStartData(contentBlockIndex));
            }
            appendEvent(output, AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_DELTA,
                    textDeltaData(contentBlockIndex, text));
        }

        private int contentKey(JsonNode root) {
            int outputIdx = nonNegativeIntOrZero(root.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX));
            int contentIdx = nonNegativeIntOrZero(root.get(OpenAiResponsesJsonPolicy.FIELD_CONTENT_INDEX));
            return outputIdx * 10_000 + contentIdx;
        }

        @Override public boolean isDone() { return done; }
        @Override public int getInputTokens() { return inputTokens; }
        @Override public int getOutputTokens() { return outputTokens; }
    }

    // ========================
    // 辅助方法：requestFromIR
    // ========================

    /**
     * function_call input item → assistant message with tool_use block。
     */
    private static JsonNode convertFunctionCallToAssistantMsg(JsonNode item) {
        if (isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID))
                || isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) {
            log.debug("Responses→Anthropic request: function_call missing call_id or name, ignored");
            return null;
        }
        ObjectNode msg = JSON.createObjectNode();
        msg.put(AnthropicMessagesBodyPolicy.FIELD_ROLE, AnthropicMessagesBodyPolicy.ROLE_ASSISTANT);
        ArrayNode content = JSON.createArrayNode();
        ObjectNode toolUse = JSON.createObjectNode();
        toolUse.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TOOL_USE);
        toolUse.put(AnthropicMessagesBodyPolicy.FIELD_ID,
                GatewayToolCallIdPolicy.fromResponsesCallIdForAnthropicRequest(
                        item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID).asText()));
        toolUse.put(AnthropicMessagesBodyPolicy.FIELD_NAME, item.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText());
        String args = item.has(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS)
                ? item.get(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS).asText()
                : OpenAiResponsesJsonPolicy.EMPTY_JSON_OBJECT;
        args = sanitizeAnthropicToolUseInput(item.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText(), args);
        try {
            toolUse.set(AnthropicMessagesBodyPolicy.FIELD_INPUT, JSON.readTree(args));
        } catch (Exception e) {
            toolUse.put(AnthropicMessagesBodyPolicy.FIELD_INPUT, args);
        }
        content.add(toolUse);
        msg.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, content);
        return msg;
    }

    private static JsonNode convertReasoningToAssistantMsg(JsonNode item) {
        ObjectNode msg = JSON.createObjectNode();
        msg.put(AnthropicMessagesBodyPolicy.FIELD_ROLE, AnthropicMessagesBodyPolicy.ROLE_ASSISTANT);
        ArrayNode content = JSON.createArrayNode();
        addReasoningBlocks(content, item);
        msg.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, content);
        return msg;
    }

    private static String appendSystemText(String current, String next) {
        if (next == null || next.isEmpty()) return current;
        return current == null || current.isEmpty() ? next : current + "\n\n" + next;
    }

    /**
     * function_call_output input item → user message with tool_result block。
     */
    private static JsonNode convertFunctionCallOutputToUserMsg(JsonNode item) {
        if (isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID))) {
            log.debug("Responses→Anthropic request: function_call_output missing call_id, ignored");
            return null;
        }
        ObjectNode msg = JSON.createObjectNode();
        msg.put(AnthropicMessagesBodyPolicy.FIELD_ROLE, AnthropicMessagesBodyPolicy.ROLE_USER);
        ArrayNode content = JSON.createArrayNode();
        ObjectNode toolResult = JSON.createObjectNode();
        toolResult.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TOOL_RESULT);
        toolResult.put(AnthropicMessagesBodyPolicy.FIELD_TOOL_USE_ID,
                GatewayToolCallIdPolicy.fromResponsesCallIdForAnthropicRequest(
                        item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID).asText()));
        String output = textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT), "");
        if (output.isEmpty()) {
            output = OpenAiResponsesBodyPolicy.DEFAULT_EMPTY_TOOL_OUTPUT;
        }
        toolResult.put(AnthropicMessagesBodyPolicy.FIELD_CONTENT, output);
        content.add(toolResult);
        msg.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, content);
        return msg;
    }

    /**
     * Responses user content → Anthropic content（字符串或数组）。
     */
    private static JsonNode convertUserContentToAnthropic(JsonNode content) {
        if (content == null) return null;
        if (content.isTextual()) return content;

        if (content.isArray()) {
            ArrayNode blocks = JSON.createArrayNode();
            for (JsonNode part : content) {
                String partType = textOrDefault(part.get(OpenAiResponsesBodyPolicy.FIELD_TYPE), "");
                switch (partType) {
                    case OpenAiResponsesJsonPolicy.TYPE_INPUT_TEXT, OpenAiResponsesJsonPolicy.TYPE_TEXT -> {
                        if (isBlankText(part.get(OpenAiResponsesBodyPolicy.FIELD_TEXT))) {
                            break;
                        }
                        ObjectNode textBlock = JSON.createObjectNode();
                        textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
                        textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TEXT,
                                part.get(OpenAiResponsesBodyPolicy.FIELD_TEXT).asText());
                        blocks.add(textBlock);
                    }
                    case OpenAiResponsesBodyPolicy.TYPE_INPUT_IMAGE -> {
                        String imageUrl = textOrDefault(part.get(OpenAiResponsesBodyPolicy.FIELD_IMAGE_URL), "");
                        ObjectNode source = dataURIToAnthropicImageSource(imageUrl);
                        if (source != null) {
                            ObjectNode imageBlock = JSON.createObjectNode();
                            imageBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE,
                                    AnthropicMessagesBodyPolicy.TYPE_IMAGE);
                            imageBlock.set(AnthropicMessagesBodyPolicy.FIELD_SOURCE, source);
                            blocks.add(imageBlock);
                        }
                    }
                    case OpenAiResponsesJsonPolicy.TYPE_INPUT_FILE -> {
                        ObjectNode documentBlock = responsesInputFileToAnthropicDocument(part);
                        if (documentBlock != null) {
                            blocks.add(documentBlock);
                        }
                    }
                    case AnthropicMessagesBodyPolicy.TYPE_TOOL_RESULT -> {
                        JsonNode toolResult = sanitizeAnthropicToolResultBlock(part);
                        if (toolResult != null) {
                            blocks.add(toolResult);
                        }
                    }
                    default ->
                        log.debug("Responses→Anthropic: unknown user content part type '{}'", partType);
                }
            }
            if (blocks.size() == 0) return null;
            return blocks;
        }
        return null;
    }

    /**
     * Responses input_file content part → Anthropic document content block.
     * The conversion is limited to file_url, file_data, and file_id because those
     * are the stable file payload shapes both protocols can represent.
     */
    private static ObjectNode responsesInputFileToAnthropicDocument(JsonNode part) {
        if (part == null || !part.isObject()) return null;

        ObjectNode source = JSON.createObjectNode();
        if (!isBlankText(part.get(OpenAiResponsesJsonPolicy.FIELD_FILE_URL))) {
            source.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_URL);
            source.put(AnthropicMessagesBodyPolicy.FIELD_URL,
                    part.get(OpenAiResponsesJsonPolicy.FIELD_FILE_URL).asText());
        } else if (!isBlankText(part.get(OpenAiResponsesJsonPolicy.FIELD_FILE_DATA))) {
            String fileData = part.get(OpenAiResponsesJsonPolicy.FIELD_FILE_DATA).asText();
            String mediaType = OpenAiResponsesBodyPolicy.DEFAULT_DOCUMENT_MEDIA_TYPE;
            String data = fileData;
            OpenAiResponsesBodyPolicy.DataUriParts dataUri = OpenAiResponsesBodyPolicy.parseDataUri(fileData);
            if (dataUri != null) {
                mediaType = dataUri.mediaType();
                data = dataUri.data();
            }
            if (data.isBlank()) return null;
            source.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_BASE64);
            source.put(AnthropicMessagesBodyPolicy.FIELD_MEDIA_TYPE, mediaType);
            source.put(AnthropicMessagesBodyPolicy.FIELD_DATA, data);
        } else if (!isBlankText(part.get(OpenAiResponsesJsonPolicy.FIELD_FILE_ID))) {
            source.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_FILE);
            source.put(AnthropicMessagesBodyPolicy.FIELD_FILE_ID,
                    part.get(OpenAiResponsesJsonPolicy.FIELD_FILE_ID).asText());
        } else {
            return null;
        }

        ObjectNode documentBlock = JSON.createObjectNode();
        documentBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_DOCUMENT);
        if (!isBlankText(part.get(OpenAiResponsesJsonPolicy.FIELD_FILENAME))) {
            documentBlock.put(AnthropicMessagesBodyPolicy.FIELD_TITLE,
                    part.get(OpenAiResponsesJsonPolicy.FIELD_FILENAME).asText());
        }
        documentBlock.set(AnthropicMessagesBodyPolicy.FIELD_SOURCE, source);
        return documentBlock;
    }

    /**
     * Responses assistant content → Anthropic content。
     */
    private static JsonNode convertAssistantContentToAnthropic(JsonNode content) {
        if (content == null) return emptyAnthropicTextBlocks();
        if (content.isTextual()) {
            ArrayNode blocks = JSON.createArrayNode();
            ObjectNode textBlock = JSON.createObjectNode();
            textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
            textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, content.asText());
            blocks.add(textBlock);
            return blocks;
        }
        if (content.isArray()) {
            ArrayNode blocks = JSON.createArrayNode();
            for (JsonNode part : content) {
                String partType = textOrDefault(part.get(OpenAiResponsesBodyPolicy.FIELD_TYPE), "");
                if (OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT.equals(partType)
                        || OpenAiResponsesJsonPolicy.TYPE_TEXT.equals(partType)) {
                    String text = textOrDefault(part.get(OpenAiResponsesBodyPolicy.FIELD_TEXT), "");
                    if (text.isEmpty()) {
                        continue;
                    }
                    ObjectNode textBlock = JSON.createObjectNode();
                    textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
                    textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, text);
                    blocks.add(textBlock);
                } else if (AnthropicThinkingPolicy.TYPE_THINKING.equals(partType)) {
                    JsonNode thinking = sanitizeAnthropicThinkingBlock(part);
                    if (thinking != null) {
                        blocks.add(thinking);
                    }
                } else if (AnthropicThinkingPolicy.TYPE_REDACTED_THINKING.equals(partType)) {
                    JsonNode redacted = sanitizeAnthropicRedactedThinkingBlock(part);
                    if (redacted != null) {
                        blocks.add(redacted);
                    }
                } else if (AnthropicMessagesBodyPolicy.TYPE_TOOL_USE.equals(partType)) {
                    JsonNode toolUse = sanitizeAnthropicToolUseBlock(part);
                    if (toolUse != null) {
                        blocks.add(toolUse);
                    }
                }
            }
            if (blocks.size() == 0) return emptyAnthropicTextBlocks();
            return blocks;
        }
        return null;
    }

    private static ArrayNode emptyAnthropicTextBlocks() {
        ArrayNode blocks = JSON.createArrayNode();
        ObjectNode textBlock = JSON.createObjectNode();
        textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
        textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, "");
        blocks.add(textBlock);
        return blocks;
    }

    private static JsonNode sanitizeAnthropicToolResultBlock(JsonNode part) {
        if (part == null || !part.isObject()
                || isBlankText(part.get(AnthropicMessagesBodyPolicy.FIELD_TOOL_USE_ID))) return null;
        ObjectNode toolResult = JSON.createObjectNode();
        toolResult.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TOOL_RESULT);
        toolResult.put(AnthropicMessagesBodyPolicy.FIELD_TOOL_USE_ID,
                part.get(AnthropicMessagesBodyPolicy.FIELD_TOOL_USE_ID).asText());
        JsonNode content = part.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT);
        if (content == null || content.isNull()) {
            toolResult.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, JSON.createArrayNode());
        } else if (content.isTextual()) {
            toolResult.put(AnthropicMessagesBodyPolicy.FIELD_CONTENT, content.asText());
        } else if (content.isArray()) {
            ArrayNode blocks = JSON.createArrayNode();
            for (JsonNode block : content) {
                String type = textOrDefault(block.get(AnthropicMessagesBodyPolicy.FIELD_TYPE), "");
                if (AnthropicMessagesBodyPolicy.TYPE_TEXT.equals(type)
                        && block.has(AnthropicMessagesBodyPolicy.FIELD_TEXT)
                        && block.get(AnthropicMessagesBodyPolicy.FIELD_TEXT).isTextual()) {
                    ObjectNode textBlock = JSON.createObjectNode();
                    textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
                    textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TEXT,
                            block.get(AnthropicMessagesBodyPolicy.FIELD_TEXT).asText());
                    blocks.add(textBlock);
                }
            }
            toolResult.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, blocks);
        } else {
            toolResult.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, JSON.createArrayNode());
        }
        return toolResult;
    }

    private static JsonNode sanitizeAnthropicThinkingBlock(JsonNode part) {
        String thinking = textOrDefault(part.get(AnthropicThinkingPolicy.FIELD_THINKING), "");
        if (thinking.isEmpty()) return null;
        ObjectNode block = JSON.createObjectNode();
        block.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicThinkingPolicy.TYPE_THINKING);
        block.put(AnthropicThinkingPolicy.FIELD_THINKING, thinking);
        copyTextIfExists(part, block, AnthropicThinkingPolicy.FIELD_SIGNATURE);
        return block;
    }

    private static JsonNode sanitizeAnthropicRedactedThinkingBlock(JsonNode part) {
        String data = textOrDefault(part.get(AnthropicThinkingPolicy.FIELD_DATA), "");
        if (data.isEmpty()) return null;
        ObjectNode block = JSON.createObjectNode();
        block.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicThinkingPolicy.TYPE_REDACTED_THINKING);
        block.put(AnthropicThinkingPolicy.FIELD_DATA, data);
        return block;
    }

    private static JsonNode sanitizeAnthropicToolUseBlock(JsonNode part) {
        if (part == null || !part.isObject()
                || isBlankText(part.get(AnthropicMessagesBodyPolicy.FIELD_ID))
                || isBlankText(part.get(AnthropicMessagesBodyPolicy.FIELD_NAME))) {
            return null;
        }
        ObjectNode block = JSON.createObjectNode();
        block.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TOOL_USE);
        block.put(AnthropicMessagesBodyPolicy.FIELD_ID, part.get(AnthropicMessagesBodyPolicy.FIELD_ID).asText());
        block.put(AnthropicMessagesBodyPolicy.FIELD_NAME, part.get(AnthropicMessagesBodyPolicy.FIELD_NAME).asText());
        block.set(AnthropicMessagesBodyPolicy.FIELD_INPUT,
                part.has(AnthropicMessagesBodyPolicy.FIELD_INPUT)
                        && part.get(AnthropicMessagesBodyPolicy.FIELD_INPUT).isObject()
                ? part.get(AnthropicMessagesBodyPolicy.FIELD_INPUT)
                : JSON.createObjectNode());
        return block;
    }

    /**
     * data URI → Anthropic image source。
     */
    private static ObjectNode dataURIToAnthropicImageSource(String dataUri) {
        if (dataUri == null || dataUri.isBlank()) return null;
        if (dataUri.startsWith("http://") || dataUri.startsWith("https://")) {
            ObjectNode source = JSON.createObjectNode();
            source.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_URL);
            source.put(AnthropicMessagesBodyPolicy.FIELD_URL, dataUri);
            return source;
        }
        OpenAiResponsesBodyPolicy.DataUriParts parts = OpenAiResponsesBodyPolicy.parseDataUri(dataUri);
        if (parts == null) return null;
        try {
            String mediaType = parts.mediaType();
            String data = parts.data();
            if (data.isEmpty()) return null;

            ObjectNode source = JSON.createObjectNode();
            source.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_BASE64);
            source.put(AnthropicMessagesBodyPolicy.FIELD_MEDIA_TYPE, mediaType);
            source.put(AnthropicMessagesBodyPolicy.FIELD_DATA, data);
            return source;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 合并相邻同角色消息（Anthropic 要求 user/assistant 交替）。
     */
    private static ArrayNode mergeConsecutiveMessages(ArrayNode messages) {
        if (messages.size() <= 1) return messages;

        ArrayNode merged = JSON.createArrayNode();
        JsonNode prev = null;
        String prevRole = null;

        for (JsonNode msg : messages) {
            String role = msg.has(AnthropicMessagesBodyPolicy.FIELD_ROLE)
                    ? msg.get(AnthropicMessagesBodyPolicy.FIELD_ROLE).asText()
                    : AnthropicMessagesBodyPolicy.ROLE_USER;
            if (prev != null && role.equals(prevRole)) {
                // 合并 content
                prev = mergeMessageContent(prev, msg);
            } else {
                if (prev != null) merged.add(prev);
                prev = msg.deepCopy();
                prevRole = role;
            }
        }
        if (prev != null) merged.add(prev);
        return merged;
    }

    private static JsonNode mergeMessageContent(JsonNode prev, JsonNode next) {
        JsonNode prevContent = prev.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT);
        JsonNode nextContent = next.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT);

        ArrayNode mergedContent = JSON.createArrayNode();
        addAllContentBlocks(mergedContent, prevContent);
        addAllContentBlocks(mergedContent, nextContent);

        ObjectNode result = prev.deepCopy();
        result.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, mergedContent);
        return result;
    }

    private static void addAllContentBlocks(ArrayNode target, JsonNode content) {
        if (content == null) return;
        if (content.isArray()) {
            for (JsonNode block : content) {
                target.add(block);
            }
        } else if (content.isTextual()) {
            ObjectNode textBlock = JSON.createObjectNode();
            textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
            textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, content.asText());
            target.add(textBlock);
        }
    }

    /**
     * 转换 Responses tool 到 Anthropic tool。
     */
    private static JsonNode convertResponsesToolToAnthropic(JsonNode tool) {
        String toolType = textOrDefault(tool.get(OpenAiResponsesBodyPolicy.FIELD_TYPE), "");
        // web_search 系列 → Anthropic web_search tool
        if (GatewayWebSearchToolPolicy.isWebSearchToolType(toolType)) {
            ObjectNode ws = JSON.createObjectNode();
            ws.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, GatewayWebSearchToolPolicy.TYPE_WEB_SEARCH_20250305);
            ws.put(AnthropicMessagesBodyPolicy.FIELD_NAME, GatewayWebSearchToolPolicy.TOOL_NAME_WEB_SEARCH);
            ObjectNode userLocation = normalizeWebSearchUserLocation(
                    tool.get(OpenAiResponsesBodyPolicy.FIELD_USER_LOCATION));
            if (userLocation != null) {
                ws.set(AnthropicMessagesBodyPolicy.FIELD_USER_LOCATION, userLocation);
            }
            return ws;
        }
        // function tool → Anthropic tool
        if (OpenAiResponsesBodyPolicy.TOOL_CHOICE_FUNCTION.equals(toolType)) {
            if (isBlankText(tool.get(AnthropicMessagesBodyPolicy.FIELD_NAME))) return null;
            ObjectNode func = JSON.createObjectNode();
            func.put(AnthropicMessagesBodyPolicy.FIELD_NAME, tool.get(AnthropicMessagesBodyPolicy.FIELD_NAME).asText());
            copyTextIfExists(tool, func, OpenAiResponsesBodyPolicy.FIELD_DESCRIPTION);
            JsonNode params = tool.has(OpenAiResponsesBodyPolicy.FIELD_PARAMETERS)
                    ? tool.get(OpenAiResponsesBodyPolicy.FIELD_PARAMETERS)
                    : null;
            func.set(AnthropicMessagesBodyPolicy.FIELD_INPUT_SCHEMA, normalizeInputSchema(params));
            return func;
        }

        if (toolType.isBlank()) {
            return null;
        }
        ObjectNode passthrough = JSON.createObjectNode();
        passthrough.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, toolType);
        copyTextIfExists(tool, passthrough, AnthropicMessagesBodyPolicy.FIELD_NAME);
        copyTextIfExists(tool, passthrough, OpenAiResponsesBodyPolicy.FIELD_DESCRIPTION);
        if (tool.has(OpenAiResponsesBodyPolicy.FIELD_PARAMETERS)) {
            passthrough.set(AnthropicMessagesBodyPolicy.FIELD_INPUT_SCHEMA,
                    tool.get(OpenAiResponsesBodyPolicy.FIELD_PARAMETERS));
        }
        return passthrough;
    }

    private static JsonNode normalizeInputSchema(JsonNode schema) {
        if (schema == null || schema.isNull() || (schema.isTextual() && "null".equals(schema.asText()))) {
            ObjectNode empty = JSON.createObjectNode();
            empty.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_OBJECT);
            empty.set(OpenAiResponsesJsonPolicy.FIELD_PROPERTIES, JSON.createObjectNode());
            return empty;
        }
        if (!schema.isObject()) {
            return schema.deepCopy();
        }
        return schema;
    }

    /**
     * 转换 Responses tool_choice 到 Anthropic tool_choice。
     */
    private static JsonNode convertResponsesToolChoiceToAnthropic(JsonNode toolChoice) {
        if (toolChoice.isTextual()) {
            String tc = toolChoice.asText();
            return switch (tc) {
                case OpenAiResponsesBodyPolicy.TOOL_CHOICE_AUTO -> {
                    ObjectNode obj = JSON.createObjectNode();
                    obj.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_AUTO);
                    yield obj;
                }
                case OpenAiResponsesBodyPolicy.TOOL_CHOICE_REQUIRED -> {
                    ObjectNode obj = JSON.createObjectNode();
                    obj.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_ANY);
                    yield obj;
                }
                case OpenAiResponsesBodyPolicy.TOOL_CHOICE_NONE -> {
                    ObjectNode obj = JSON.createObjectNode();
                    obj.put(AnthropicMessagesBodyPolicy.FIELD_TYPE,
                            AnthropicMessagesBodyPolicy.TYPE_TOOL_CHOICE_NONE);
                    yield obj;
                }
                default -> toolChoice.deepCopy();
            };
        }
        if (toolChoice.isObject() && OpenAiResponsesBodyPolicy.TOOL_CHOICE_FUNCTION.equals(
                textOrDefault(toolChoice.get(OpenAiResponsesBodyPolicy.FIELD_TYPE), ""))) {
            JsonNode nameNode = toolChoice.get(AnthropicMessagesBodyPolicy.FIELD_NAME);
            if (isBlankText(nameNode) && toolChoice.has(OpenAiChatCompletionsBodyPolicy.FIELD_FUNCTION)
                    && toolChoice.get(OpenAiChatCompletionsBodyPolicy.FIELD_FUNCTION).isObject()) {
                nameNode = toolChoice.get(OpenAiChatCompletionsBodyPolicy.FIELD_FUNCTION)
                        .get(AnthropicMessagesBodyPolicy.FIELD_NAME);
            }
            if (isBlankText(nameNode)) return toolChoice.deepCopy();
            ObjectNode obj = JSON.createObjectNode();
            obj.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TOOL_CHOICE_TOOL);
            obj.put(AnthropicMessagesBodyPolicy.FIELD_NAME, nameNode.asText());
            return obj;
        }
        return toolChoice.isNull() ? null : toolChoice.deepCopy();
    }

    /**
     * Responses effort → Anthropic effort。
     * minimal→low，low/medium/high 保持同名，xhigh→max；none/未知值不透传。
     */
    private static String mapResponsesEffortToAnthropic(JsonNode effort) {
        if (effort == null || !effort.isTextual()) return null;
        return switch (effort.asText()) {
            case "minimal", "low" -> "low";
            case "medium" -> "medium";
            case "high" -> "high";
            case "xhigh" -> "max";
            default -> null;
        };
    }

    private static ObjectNode normalizeWebSearchUserLocation(JsonNode userLocation) {
        if (userLocation == null || !userLocation.isObject()) return null;
        ObjectNode normalized = JSON.createObjectNode();
        copyTextIfExists(userLocation, normalized, OpenAiResponsesBodyPolicy.FIELD_TYPE);
        copyTextIfExists(userLocation, normalized, OpenAiResponsesBodyPolicy.FIELD_COUNTRY);
        copyTextIfExists(userLocation, normalized, OpenAiResponsesBodyPolicy.FIELD_REGION);
        copyTextIfExists(userLocation, normalized, OpenAiResponsesBodyPolicy.FIELD_CITY);
        copyTextIfExists(userLocation, normalized, OpenAiResponsesBodyPolicy.FIELD_TIMEZONE);
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 默认 thinking budget_tokens。
     */
    private static int defaultThinkingBudget(String effort) {
        return switch (effort) {
            case "low" -> 1024;
            case "medium" -> 4096;
            case "high" -> 10240;
            case "max" -> 32768;
            default -> 10240;
        };
    }

    // ========================
    // 辅助方法：responseFromIR
    // ========================

    /**
     * Responses status → Anthropic stop_reason。
     */
    private static String mapResponsesStatusToAnthropicStopReason(String status, JsonNode ir, boolean hasToolUse) {
        if (OpenAiResponsesJsonPolicy.STATUS_INCOMPLETE.equals(status)) {
            if (OpenAiResponsesJsonPolicy.DEFAULT_INCOMPLETE_REASON.equals(
                    textOrDefault(ir.path(OpenAiResponsesJsonPolicy.FIELD_INCOMPLETE_DETAILS)
                            .get(OpenAiResponsesJsonPolicy.FIELD_REASON), ""))) {
                return AnthropicMessagesBodyPolicy.STOP_REASON_MAX_TOKENS;
            }
            return AnthropicMessagesBodyPolicy.STOP_REASON_END_TURN;
        }
        if (OpenAiResponsesJsonPolicy.STATUS_COMPLETED.equals(status)) {
            return hasToolUse
                    ? AnthropicMessagesBodyPolicy.STOP_REASON_TOOL_USE
                    : AnthropicMessagesBodyPolicy.STOP_REASON_END_TURN;
        }
        return AnthropicMessagesBodyPolicy.STOP_REASON_END_TURN;
    }

    private static String extractReasoningText(JsonNode item) {
        StringBuilder text = new StringBuilder();
        if (item.has(OpenAiResponsesJsonPolicy.FIELD_CONTENT)
                && item.get(OpenAiResponsesJsonPolicy.FIELD_CONTENT).isArray()) {
            for (JsonNode part : item.get(OpenAiResponsesJsonPolicy.FIELD_CONTENT)) {
                String type = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), "");
                String partText = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_TEXT), "");
                if ((OpenAiResponsesJsonPolicy.TYPE_REASONING_TEXT.equals(type)
                        || OpenAiResponsesJsonPolicy.TYPE_TEXT.equals(type)) && !partText.isEmpty()) {
                    if (text.length() > 0) text.append("\n");
                    text.append(partText);
                }
            }
        }
        if (text.length() > 0) return text.toString();

        return extractReasoningSummaryText(item);
    }

    private static String extractReasoningSummaryText(JsonNode item) {
        StringBuilder text = new StringBuilder();
        if (item.has(OpenAiResponsesJsonPolicy.FIELD_SUMMARY)
                && item.get(OpenAiResponsesJsonPolicy.FIELD_SUMMARY).isArray()) {
            for (JsonNode summary : item.get(OpenAiResponsesJsonPolicy.FIELD_SUMMARY)) {
                String summaryText = textOrDefault(summary.get(OpenAiResponsesJsonPolicy.FIELD_TEXT), "");
                if (!OpenAiResponsesJsonPolicy.TYPE_SUMMARY_TEXT.equals(
                        textOrDefault(summary.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), ""))
                        || summaryText.isEmpty()) continue;
                text.append(summaryText);
            }
        }
        return text.toString();
    }

    private static void addResponseReasoningBlocks(ArrayNode content, JsonNode item) {
        String text = extractReasoningSummaryText(item);
        if (!text.isEmpty()) {
            ObjectNode thinkingBlock = JSON.createObjectNode();
            thinkingBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicThinkingPolicy.TYPE_THINKING);
            thinkingBlock.put(AnthropicThinkingPolicy.FIELD_THINKING, text);
            content.add(thinkingBlock);
        }
    }

    private static void addReasoningBlocks(ArrayNode content, JsonNode item) {
        String text = extractReasoningText(item);
        String encryptedContent = textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_ENCRYPTED_CONTENT), "");

        if (!text.isEmpty()
                || item.has(OpenAiResponsesJsonPolicy.FIELD_CONTENT)
                || item.has(OpenAiResponsesJsonPolicy.FIELD_SUMMARY)) {
            ObjectNode thinkingBlock = JSON.createObjectNode();
            thinkingBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicThinkingPolicy.TYPE_THINKING);
            thinkingBlock.put(AnthropicThinkingPolicy.FIELD_THINKING, text);
            if (!encryptedContent.isEmpty()) {
                thinkingBlock.put(AnthropicThinkingPolicy.FIELD_SIGNATURE, encryptedContent);
            }
            content.add(thinkingBlock);
            return;
        }

        if (!encryptedContent.isEmpty()) {
            ObjectNode redactedBlock = JSON.createObjectNode();
            redactedBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE,
                    AnthropicThinkingPolicy.TYPE_REDACTED_THINKING);
            redactedBlock.put(AnthropicThinkingPolicy.FIELD_DATA, encryptedContent);
            content.add(redactedBlock);
        }
    }

    private static void addWebSearchBlocks(ArrayNode content, JsonNode item) {
        String sourceId = textOrDefault(item.get("id"), null);
        if (sourceId == null) {
            sourceId = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        }
        String toolUseId = AnthropicMessagesBodyPolicy.WEB_SEARCH_SERVER_TOOL_ID_PREFIX + sourceId;

        ObjectNode serverToolUse = JSON.createObjectNode();
        serverToolUse.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_SERVER_TOOL_USE);
        serverToolUse.put(AnthropicMessagesBodyPolicy.FIELD_ID, toolUseId);
        serverToolUse.put(AnthropicMessagesBodyPolicy.FIELD_NAME, AnthropicMessagesBodyPolicy.TOOL_NAME_WEB_SEARCH);
        ObjectNode input = JSON.createObjectNode();
        JsonNode action = item.get(OpenAiResponsesJsonPolicy.FIELD_ACTION);
        String query = "";
        if (action != null && action.isObject() && !isBlankText(action.get(OpenAiResponsesJsonPolicy.FIELD_QUERY))) {
            query = action.get(OpenAiResponsesJsonPolicy.FIELD_QUERY).asText();
        }
        input.put(OpenAiResponsesJsonPolicy.FIELD_QUERY, query);
        serverToolUse.set(AnthropicMessagesBodyPolicy.FIELD_INPUT, input);
        content.add(serverToolUse);

        ObjectNode toolResult = JSON.createObjectNode();
        toolResult.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_WEB_SEARCH_TOOL_RESULT);
        toolResult.put(AnthropicMessagesBodyPolicy.FIELD_TOOL_USE_ID, toolUseId);
        toolResult.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, JSON.createArrayNode());
        content.add(toolResult);
    }

    /**
     * Read 工具空 pages 字段清理。
     * 仅当 tool name 为 "Read" 且 pages 为 JSON 空字符串时删除该字段。
     */
    private static String sanitizeAnthropicToolUseInput(String toolName, String arguments) {
        if (!AnthropicMessagesBodyPolicy.TOOL_NAME_READ.equals(toolName)) return arguments;
        try {
            JsonNode args = JSON.readTree(arguments);
            if (args.isObject() && args.has("pages")) {
                JsonNode pages = args.get("pages");
                if (pages.isTextual() && pages.asText().isEmpty()) {
                    ((ObjectNode) args).remove("pages");
                    return args.toString();
                }
            }
        } catch (Exception e) {
            // 非 JSON，保持原样
        }
        return arguments;
    }

    /**
     * 从 content 提取纯文本（支持字符串和数组格式）。
     */
    private static String extractTextFromContent(JsonNode content) {
        if (content == null) return null;
        if (content.isTextual()) return content.asText().isBlank() ? null : content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String partType = part.has(OpenAiResponsesJsonPolicy.FIELD_TYPE)
                        ? part.get(OpenAiResponsesJsonPolicy.FIELD_TYPE).asText()
                        : "";
                if (OpenAiResponsesJsonPolicy.TYPE_INPUT_TEXT.equals(partType)
                        || OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT.equals(partType)
                        || OpenAiResponsesJsonPolicy.TYPE_TEXT.equals(partType)) {
                    if (!isBlankText(part.get(OpenAiResponsesJsonPolicy.FIELD_TEXT))) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(part.get(OpenAiResponsesJsonPolicy.FIELD_TEXT).asText());
                    }
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        return null;
    }

    private static ObjectNode ephemeralCacheControl() {
        ObjectNode cacheControl = JSON.createObjectNode();
        cacheControl.put(AnthropicMessagesBodyPolicy.FIELD_TYPE,
                AnthropicMessagesBodyPolicy.CACHE_CONTROL_TYPE_EPHEMERAL);
        return cacheControl;
    }

    /**
     * Responses 的 prompt_cache_key 不会被 Anthropic 识别。转 Claude 时将其降级为
     * Anthropic prompt caching 的 cache_control 断点，优先放在稳定 system 前缀；
     * 无 system 时才标记首个 user text block。
     */
    private static void attachCacheControlToFirstUserTextBlock(ArrayNode messages) {
        for (JsonNode message : messages) {
            if (!message.isObject()
                    || !AnthropicMessagesBodyPolicy.ROLE_USER.equals(
                    textOrDefault(message.get(AnthropicMessagesBodyPolicy.FIELD_ROLE), null))) {
                continue;
            }

            ObjectNode messageObj = (ObjectNode) message;
            JsonNode content = messageObj.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT);
            if (content == null || content.isNull()) {
                continue;
            }

            if (content.isTextual()) {
                if (content.asText().isBlank()) {
                    continue;
                }
                ArrayNode blocks = JSON.createArrayNode();
                ObjectNode textBlock = JSON.createObjectNode();
                textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
                textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, content.asText());
                textBlock.set(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL, ephemeralCacheControl());
                blocks.add(textBlock);
                messageObj.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, blocks);
                return;
            }

            if (content.isArray()) {
                for (JsonNode block : content) {
                    if (!block.isObject()
                            || !AnthropicMessagesBodyPolicy.TYPE_TEXT.equals(
                            textOrDefault(block.get(AnthropicMessagesBodyPolicy.FIELD_TYPE), ""))
                            || isBlankText(block.get(AnthropicMessagesBodyPolicy.FIELD_TEXT))) {
                        continue;
                    }
                    ObjectNode blockObj = (ObjectNode) block;
                    if (!blockObj.has(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL)) {
                        blockObj.set(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL, ephemeralCacheControl());
                    }
                    return;
                }
            }
        }
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

    private static boolean hasText(JsonNode src, String field) {
        return src.has(field) && src.get(field).isTextual() && !src.get(field).asText().isBlank();
    }

    private static boolean isBlankText(JsonNode node) {
        return node == null || !node.isTextual() || node.asText().isBlank();
    }

    private static String textOrDefault(JsonNode node, String defaultValue) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : defaultValue;
    }

    private static boolean isPositiveInt(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.asInt() > 0;
    }

    private static boolean isNonNegativeInt(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.asInt() >= 0;
    }

    private static int nonNegativeIntOrZero(JsonNode node) {
        return isNonNegativeInt(node) ? node.asInt() : 0;
    }

    private static JsonNode normalizeStopSequences(JsonNode stop) {
        if (stop == null || !stop.isArray()) return null;
        ArrayNode arr = JSON.createArrayNode();
        for (JsonNode item : stop) {
            if (item.isTextual() && !item.asText().isBlank()) {
                arr.add(item.asText());
            }
        }
        return arr.isEmpty() ? null : arr;
    }

    private static void appendEvent(List<String> output, String event, JsonNode data) {
        output.add(AnthropicMessagesSsePolicy.EVENT_LINE_PREFIX + event);
        try {
            output.add(AnthropicMessagesSsePolicy.DATA_LINE_PREFIX + JSON.writeValueAsString(data));
        } catch (Exception e) {
            log.debug("IR→Anthropic SSE encode error: {}", e.getMessage());
            return;
        }
        output.add(AnthropicMessagesSsePolicy.FRAME_SEPARATOR_LINE);
    }

    private static ObjectNode eventData(String type) {
        ObjectNode data = JSON.createObjectNode();
        data.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, type);
        return data;
    }

    private static ObjectNode messageStartData(String messageId, String model) {
        ObjectNode data = eventData(AnthropicMessagesSsePolicy.EVENT_MESSAGE_START);
        ObjectNode message = JSON.createObjectNode();
        message.put(AnthropicMessagesBodyPolicy.FIELD_ID, messageId);
        message.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_MESSAGE);
        message.put(AnthropicMessagesBodyPolicy.FIELD_ROLE, AnthropicMessagesBodyPolicy.ROLE_ASSISTANT);
        message.put(AnthropicMessagesBodyPolicy.FIELD_MODEL, model);
        message.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, JSON.createArrayNode());
        message.putNull(AnthropicMessagesBodyPolicy.FIELD_STOP_REASON);
        message.putNull(AnthropicMessagesBodyPolicy.FIELD_STOP_SEQUENCE);
        ObjectNode usage = JSON.createObjectNode();
        usage.put(AnthropicMessagesBodyPolicy.FIELD_INPUT_TOKENS, 0);
        usage.put(AnthropicMessagesBodyPolicy.FIELD_OUTPUT_TOKENS, 0);
        message.set(AnthropicMessagesBodyPolicy.FIELD_USAGE, usage);
        data.set(AnthropicMessagesBodyPolicy.FIELD_MESSAGE, message);
        return data;
    }

    private static ObjectNode contentBlockStartData(int index, ObjectNode contentBlock) {
        ObjectNode data = eventData(AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_START);
        data.put("index", index);
        data.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT_BLOCK, contentBlock);
        return data;
    }

    private static ObjectNode textBlockStartData(int index) {
        ObjectNode block = JSON.createObjectNode();
        block.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
        block.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, "");
        return contentBlockStartData(index, block);
    }

    private static ObjectNode thinkingBlockStartData(int index) {
        ObjectNode block = JSON.createObjectNode();
        block.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicThinkingPolicy.TYPE_THINKING);
        block.put(AnthropicThinkingPolicy.FIELD_THINKING, "");
        return contentBlockStartData(index, block);
    }

    private static ObjectNode toolUseBlockStartData(int index, String callId, String toolName) {
        ObjectNode block = JSON.createObjectNode();
        block.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TOOL_USE);
        block.put(AnthropicMessagesBodyPolicy.FIELD_ID, callId);
        block.put(AnthropicMessagesBodyPolicy.FIELD_NAME, toolName);
        block.set(AnthropicMessagesBodyPolicy.FIELD_INPUT, JSON.createObjectNode());
        return contentBlockStartData(index, block);
    }

    private static ObjectNode serverToolUseBlockStartData(int index, String toolUseId, String query) {
        ObjectNode block = JSON.createObjectNode();
        block.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_SERVER_TOOL_USE);
        block.put(AnthropicMessagesBodyPolicy.FIELD_ID, toolUseId);
        block.put(AnthropicMessagesBodyPolicy.FIELD_NAME, AnthropicMessagesBodyPolicy.TOOL_NAME_WEB_SEARCH);
        ObjectNode input = JSON.createObjectNode();
        input.put(OpenAiResponsesJsonPolicy.FIELD_QUERY, query);
        block.set(AnthropicMessagesBodyPolicy.FIELD_INPUT, input);
        return contentBlockStartData(index, block);
    }

    private static ObjectNode webSearchToolResultBlockStartData(int index, String toolUseId) {
        ObjectNode block = JSON.createObjectNode();
        block.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_WEB_SEARCH_TOOL_RESULT);
        block.put(AnthropicMessagesBodyPolicy.FIELD_TOOL_USE_ID, toolUseId);
        block.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, JSON.createArrayNode());
        return contentBlockStartData(index, block);
    }

    private static ObjectNode contentBlockStopData(int index) {
        ObjectNode data = eventData(AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_STOP);
        data.put("index", index);
        return data;
    }

    private static ObjectNode contentBlockDeltaData(int index, ObjectNode delta) {
        ObjectNode data = eventData(AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_DELTA);
        data.put("index", index);
        data.set(AnthropicMessagesBodyPolicy.FIELD_DELTA, delta);
        return data;
    }

    private static ObjectNode textDeltaData(int index, String text) {
        ObjectNode delta = JSON.createObjectNode();
        delta.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT_DELTA);
        delta.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, text);
        return contentBlockDeltaData(index, delta);
    }

    private static ObjectNode inputJsonDeltaData(int index, String partialJson) {
        ObjectNode delta = JSON.createObjectNode();
        delta.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_INPUT_JSON_DELTA);
        delta.put(AnthropicMessagesBodyPolicy.FIELD_PARTIAL_JSON, partialJson);
        return contentBlockDeltaData(index, delta);
    }

    private static ObjectNode thinkingDeltaData(int index, String thinking) {
        ObjectNode delta = JSON.createObjectNode();
        delta.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_THINKING_DELTA);
        delta.put(AnthropicThinkingPolicy.FIELD_THINKING, thinking);
        return contentBlockDeltaData(index, delta);
    }

    private static ObjectNode messageDeltaData(String stopReason, int outputTokens, int cacheReadInputTokens) {
        ObjectNode data = eventData(AnthropicMessagesSsePolicy.EVENT_MESSAGE_DELTA);
        ObjectNode delta = JSON.createObjectNode();
        delta.put(AnthropicMessagesBodyPolicy.FIELD_STOP_REASON, stopReason);
        delta.putNull(AnthropicMessagesBodyPolicy.FIELD_STOP_SEQUENCE);
        data.set(AnthropicMessagesBodyPolicy.FIELD_DELTA, delta);

        ObjectNode usage = JSON.createObjectNode();
        usage.put(AnthropicMessagesBodyPolicy.FIELD_OUTPUT_TOKENS, outputTokens);
        if (cacheReadInputTokens > 0) {
            usage.put(AnthropicMessagesBodyPolicy.FIELD_CACHE_READ_INPUT_TOKENS, cacheReadInputTokens);
        }
        data.set(AnthropicMessagesBodyPolicy.FIELD_USAGE, usage);
        return data;
    }

    private static ObjectNode messageStopData() {
        return eventData(AnthropicMessagesSsePolicy.EVENT_MESSAGE_STOP);
    }
}
