package com.landgate.trigger.gateway.converter.compat;

import com.landgate.trigger.gateway.converter.AnthropicConverter;
import com.landgate.trigger.gateway.converter.StreamTranslator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.AnthropicMessagesSsePolicy;
import com.landgate.types.gateway.AnthropicThinkingPolicy;
import com.landgate.types.gateway.CompatPromptCacheKeyPolicy;
import com.landgate.types.gateway.GatewayProtocolIrPolicy;
import com.landgate.types.gateway.GatewayToolCallIdPolicy;
import com.landgate.types.gateway.GatewayWebSearchToolPolicy;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;
import com.landgate.types.gateway.OpenAiResponsesJsonPolicy;
import com.landgate.types.gateway.OpenAiResponsesSsePolicy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Anthropic Messages API → OpenAI Responses API（IR）转换器。
 * <p>
 * 负责请求转换、非流式响应转换、流式 SSE 翻译。
 * 由 {@link AnthropicConverter} 门面委托调用，不作为独立 Spring Bean。
 *
 * <p>参照：sub2api {@code anthropic_to_responses.go} + {@code anthropic_to_responses_response.go}
 */
@Slf4j
public class AnthropicToResponsesConverter {

    private static final ObjectMapper JSON = new ObjectMapper();
    // ========================
    // 请求转换：Anthropic → Responses IR
    // ========================

    /**
     * 将 Anthropic Messages 请求转换为 Responses API 请求（IR）。
     *
     * @param body Anthropic 请求 JSON 字符串
     * @return Responses 格式的 JsonNode
     */
    public JsonNode requestToIR(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();
            boolean parallelToolCallsDisabled = false;

            // --- 基础字段透传 ---
            copyTextIfExists(src, dst, AnthropicMessagesBodyPolicy.FIELD_MODEL);
            copyBooleanIfExists(src, dst, AnthropicMessagesBodyPolicy.FIELD_STREAM);

            // 协议转换层不根据模型名猜能力；采样参数按客户端显式请求保留。
            copyNumberIfExists(src, dst, OpenAiResponsesBodyPolicy.FIELD_TEMPERATURE);
            copyNumberIfExists(src, dst, OpenAiResponsesBodyPolicy.FIELD_TOP_P);

            String promptCacheKey = CompatPromptCacheKeyPolicy.deriveAnthropicCompatPromptCacheKey(src);
            if (!promptCacheKey.isEmpty()) {
                dst.put(OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_KEY, promptCacheKey);
            }

            // max_tokens → max_output_tokens. sub2api clamps tiny values because
            // OpenAI Responses/Codex rejects very small output budgets.
            if (src.has(AnthropicMessagesBodyPolicy.FIELD_MAX_TOKENS)
                    && isPositiveInt(src.get(AnthropicMessagesBodyPolicy.FIELD_MAX_TOKENS))) {
                dst.put(OpenAiResponsesBodyPolicy.FIELD_MAX_OUTPUT_TOKENS,
                        Math.max(src.get(AnthropicMessagesBodyPolicy.FIELD_MAX_TOKENS).asInt(),
                                OpenAiResponsesBodyPolicy.MIN_MAX_OUTPUT_TOKENS));
            }

            // stop_sequences → 内部 IR 扩展。Responses 上游不直接消费，跨协议转 Chat/Anthropic 时再还原。
            if (src.has(AnthropicMessagesBodyPolicy.FIELD_STOP_SEQUENCES)
                    && src.get(AnthropicMessagesBodyPolicy.FIELD_STOP_SEQUENCES).isArray()) {
                JsonNode stopSequences = normalizeStopSequences(
                        src.get(AnthropicMessagesBodyPolicy.FIELD_STOP_SEQUENCES));
                if (stopSequences != null) {
                    dst.set(GatewayProtocolIrPolicy.FIELD_STOP_SEQUENCES, stopSequences);
                }
            }

            // --- system + messages → input[] ---
            ArrayNode input = JSON.createArrayNode();

            // system → developer message（input[0]）
            if (src.has(AnthropicMessagesBodyPolicy.FIELD_SYSTEM)) {
                List<String> systemTexts = extractSystemTexts(src.get(AnthropicMessagesBodyPolicy.FIELD_SYSTEM));
                if (!systemTexts.isEmpty()) {
                    ObjectNode developerMsg = JSON.createObjectNode();
                    developerMsg.put(OpenAiResponsesBodyPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TYPE_MESSAGE);
                    developerMsg.put(OpenAiResponsesBodyPolicy.FIELD_ROLE, OpenAiResponsesBodyPolicy.ROLE_DEVELOPER);
                    ArrayNode devContent = JSON.createArrayNode();
                    for (String text : systemTexts) {
                        ObjectNode part = JSON.createObjectNode();
                        part.put(OpenAiResponsesBodyPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TYPE_INPUT_TEXT);
                        part.put(OpenAiResponsesBodyPolicy.FIELD_TEXT, text);
                        devContent.add(part);
                    }
                    developerMsg.set(OpenAiResponsesBodyPolicy.FIELD_CONTENT, devContent);
                    input.add(developerMsg);
                }
            }

            // messages[] → input[]
            if (src.has(AnthropicMessagesBodyPolicy.FIELD_MESSAGES)
                    && src.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGES).isArray()) {
                for (JsonNode msg : src.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGES)) {
                    String role = msg.has(AnthropicMessagesBodyPolicy.FIELD_ROLE)
                            ? msg.get(AnthropicMessagesBodyPolicy.FIELD_ROLE).asText()
                            : AnthropicMessagesBodyPolicy.ROLE_USER;
                    JsonNode contentNode = msg.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT);

                    switch (role) {
                        case AnthropicMessagesBodyPolicy.ROLE_USER -> convertUserMessage(contentNode, input);
                        case AnthropicMessagesBodyPolicy.ROLE_ASSISTANT -> convertAssistantMessage(contentNode, input);
                        default -> convertUserMessage(contentNode, input); // 未知 role fallback 到 user
                    }
                }
            }
            dst.set(OpenAiResponsesBodyPolicy.FIELD_INPUT, input);

            // --- tools ---
            if (src.has(AnthropicMessagesBodyPolicy.FIELD_TOOLS)
                    && src.get(AnthropicMessagesBodyPolicy.FIELD_TOOLS).isArray()) {
                ArrayNode responsesTools = JSON.createArrayNode();
                for (JsonNode tool : src.get(AnthropicMessagesBodyPolicy.FIELD_TOOLS)) {
                    JsonNode convertedTool = convertAnthropicToolToResponses(tool);
                    if (convertedTool != null) {
                        responsesTools.add(convertedTool);
                    }
                }
                if (responsesTools.size() > 0) {
                    dst.set(OpenAiResponsesBodyPolicy.FIELD_TOOLS, responsesTools);
                }
            }

            // --- tool_choice ---
            if (src.has(AnthropicMessagesBodyPolicy.FIELD_TOOL_CHOICE)) {
                JsonNode disableParallel = src.get(AnthropicMessagesBodyPolicy.FIELD_TOOL_CHOICE)
                        .path(AnthropicMessagesBodyPolicy.FIELD_DISABLE_PARALLEL_TOOL_USE);
                if (disableParallel.isBoolean() && disableParallel.asBoolean()) {
                    parallelToolCallsDisabled = true;
                }
                JsonNode toolChoice = convertAnthropicToolChoiceToResponses(
                        src.get(AnthropicMessagesBodyPolicy.FIELD_TOOL_CHOICE));
                if (toolChoice != null) {
                    dst.set(OpenAiResponsesBodyPolicy.FIELD_TOOL_CHOICE, toolChoice);
                }
            }

            // --- output_config.effort → reasoning.effort ---
            // 对齐 sub2api/Codex bridge：普通 Messages 请求也默认带 medium reasoning shape。
            String reasoningEffort = "medium";
            if (src.has("output_config") && src.get("output_config").isObject()
                    && src.get("output_config").has(OpenAiResponsesBodyPolicy.FIELD_EFFORT)) {
                reasoningEffort = mapAnthropicEffortToResponses(
                        src.get("output_config").get(OpenAiResponsesBodyPolicy.FIELD_EFFORT));
            }
            if (reasoningEffort == null) {
                reasoningEffort = "medium";
            }

            // --- Codex/Responses 默认形状，对齐 sub2api anthropic bridge ---
            dst.put(OpenAiResponsesBodyPolicy.FIELD_STORE, false);
            ObjectNode text = JSON.createObjectNode();
            text.put("verbosity", OpenAiResponsesBodyPolicy.TEXT_VERBOSITY_MEDIUM);
            dst.set(OpenAiResponsesBodyPolicy.FIELD_TEXT, text);
            ObjectNode reasoning = JSON.createObjectNode();
            reasoning.put(OpenAiResponsesBodyPolicy.FIELD_EFFORT, reasoningEffort);
            reasoning.put(OpenAiResponsesBodyPolicy.FIELD_SUMMARY, OpenAiResponsesBodyPolicy.REASONING_SUMMARY_AUTO);
            dst.set(OpenAiResponsesBodyPolicy.FIELD_REASONING, reasoning);
            ArrayNode include = JSON.createArrayNode();
            include.add(OpenAiResponsesBodyPolicy.INCLUDE_REASONING_ENCRYPTED_CONTENT);
            dst.set(OpenAiResponsesBodyPolicy.FIELD_INCLUDE, include);
            if (!parallelToolCallsDisabled) {
                dst.put(OpenAiResponsesBodyPolicy.FIELD_PARALLEL_TOOL_CALLS, true);
            }
            if (parallelToolCallsDisabled) {
                dst.put(OpenAiResponsesBodyPolicy.FIELD_PARALLEL_TOOL_CALLS, false);
            }

            return dst;
        } catch (Exception e) {
            log.warn("Anthropic→Responses requestToIR error: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    // ========================
    // 非流式响应转换：Anthropic → Responses IR
    // ========================

    /**
     * 将 Anthropic 非流式响应转换为 Responses 响应（IR）。
     */
    public JsonNode responseToIR(String body) {
        try {
            JsonNode src = JSON.readTree(body);
            ObjectNode dst = JSON.createObjectNode();

            dst.put(OpenAiResponsesJsonPolicy.FIELD_ID, textOrDefault(src.get(AnthropicMessagesBodyPolicy.FIELD_ID),
                    "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24)));
            dst.put(OpenAiResponsesJsonPolicy.FIELD_OBJECT, OpenAiResponsesJsonPolicy.OBJECT_RESPONSE);
            dst.put(OpenAiResponsesJsonPolicy.FIELD_MODEL, textOrDefault(
                    src.get(AnthropicMessagesBodyPolicy.FIELD_MODEL), OpenAiResponsesJsonPolicy.DEFAULT_MODEL));

            // content[] → output[]
            ArrayNode output = JSON.createArrayNode();
            List<JsonNode> textBlocks = new ArrayList<>();

            if (src.has(AnthropicMessagesBodyPolicy.FIELD_CONTENT)
                    && src.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT).isArray()) {
                for (JsonNode block : src.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT)) {
                    String blockType = textOrDefault(block.get(AnthropicMessagesBodyPolicy.FIELD_TYPE),
                            AnthropicMessagesBodyPolicy.TYPE_TEXT);
                    switch (blockType) {
                        case AnthropicMessagesBodyPolicy.TYPE_TEXT -> textBlocks.add(block);
                        case AnthropicThinkingPolicy.TYPE_THINKING -> {
                            flushTextBlocks(output, textBlocks);
                            ObjectNode reasoning = convertAnthropicThinkingToResponsesReasoning(block);
                            if (hasReasoningPayload(reasoning)) {
                                output.add(reasoning);
                            }
                        }
                        case AnthropicThinkingPolicy.TYPE_REDACTED_THINKING -> {
                            flushTextBlocks(output, textBlocks);
                            ObjectNode reasoning = convertAnthropicRedactedThinkingToResponsesReasoning(block);
                            if (hasReasoningPayload(reasoning)) {
                                output.add(reasoning);
                            }
                        }
                        case AnthropicMessagesBodyPolicy.TYPE_TOOL_USE -> {
                            if (isBlankText(block.get(AnthropicMessagesBodyPolicy.FIELD_ID))
                                    || isBlankText(block.get(AnthropicMessagesBodyPolicy.FIELD_NAME))) {
                                log.debug("Anthropic→Responses: tool_use missing id or name, ignored");
                            } else {
                                flushTextBlocks(output, textBlocks);
                                ObjectNode funcCall = JSON.createObjectNode();
                                funcCall.put(OpenAiResponsesJsonPolicy.FIELD_TYPE,
                                        OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL);
                                funcCall.put(OpenAiResponsesJsonPolicy.FIELD_CALL_ID,
                                        GatewayToolCallIdPolicy.toResponsesCallIdFromAnthropic(
                                                block.get(AnthropicMessagesBodyPolicy.FIELD_ID).asText()));
                                funcCall.put(OpenAiResponsesJsonPolicy.FIELD_NAME,
                                        block.get(AnthropicMessagesBodyPolicy.FIELD_NAME).asText());
                                funcCall.put(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS,
                                        block.has(AnthropicMessagesBodyPolicy.FIELD_INPUT)
                                                ? block.get(AnthropicMessagesBodyPolicy.FIELD_INPUT).toString()
                                                : OpenAiResponsesJsonPolicy.EMPTY_JSON_OBJECT);
                                funcCall.put(OpenAiResponsesJsonPolicy.FIELD_STATUS,
                                        OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
                                output.add(funcCall);
                            }
                        }
                        case AnthropicMessagesBodyPolicy.TYPE_SERVER_TOOL_USE ->
                            log.debug("Anthropic→Responses: server_tool_use block ignored in non-streaming");
                        default ->
                            log.debug("Anthropic→Responses: unknown block type '{}' ignored", blockType);
                    }
                }
            }

            flushTextBlocks(output, textBlocks);
            if (output.size() == 0) {
                ObjectNode msgItem = JSON.createObjectNode();
                msgItem.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_MESSAGE);
                msgItem.put(OpenAiResponsesJsonPolicy.FIELD_ROLE, OpenAiResponsesJsonPolicy.ROLE_ASSISTANT);
                msgItem.put(OpenAiResponsesJsonPolicy.FIELD_STATUS, OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
                ArrayNode msgContent = JSON.createArrayNode();
                ObjectNode part = JSON.createObjectNode();
                part.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT);
                part.put(OpenAiResponsesJsonPolicy.FIELD_TEXT, "");
                msgContent.add(part);
                msgItem.set(OpenAiResponsesJsonPolicy.FIELD_CONTENT, msgContent);
                output.add(msgItem);
            }

            dst.set(OpenAiResponsesJsonPolicy.FIELD_OUTPUT, output);

            // stop_reason → status
            String stopReason = textOrDefault(src.get(AnthropicMessagesBodyPolicy.FIELD_STOP_REASON),
                    AnthropicMessagesBodyPolicy.STOP_REASON_END_TURN);
            String status = mapAnthropicStopReasonToResponsesStatus(stopReason);
            dst.put(OpenAiResponsesJsonPolicy.FIELD_STATUS, status);
            if (AnthropicMessagesBodyPolicy.STOP_REASON_MAX_TOKENS.equals(stopReason)) {
                ObjectNode incompleteDetails = JSON.createObjectNode();
                incompleteDetails.put(OpenAiResponsesJsonPolicy.FIELD_REASON,
                        OpenAiResponsesJsonPolicy.DEFAULT_INCOMPLETE_REASON);
                dst.set(OpenAiResponsesJsonPolicy.FIELD_INCOMPLETE_DETAILS, incompleteDetails);
            }

            // usage
            if (src.has(AnthropicMessagesBodyPolicy.FIELD_USAGE)) {
                JsonNode usage = src.get(AnthropicMessagesBodyPolicy.FIELD_USAGE);
                ObjectNode respUsage = JSON.createObjectNode();
                int inputTokens = nonNegativeIntOrZero(usage.get(AnthropicMessagesBodyPolicy.FIELD_INPUT_TOKENS));
                int outputTokens = nonNegativeIntOrZero(usage.get(AnthropicMessagesBodyPolicy.FIELD_OUTPUT_TOKENS));
                int cachedTokens = nonNegativeIntOrZero(
                        usage.get(AnthropicMessagesBodyPolicy.FIELD_CACHE_READ_INPUT_TOKENS));
                int responseInputTokens = inputTokens;
                respUsage.put(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS, responseInputTokens);
                respUsage.put(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_TOKENS, outputTokens);
                respUsage.put(OpenAiResponsesJsonPolicy.FIELD_TOTAL_TOKENS, responseInputTokens + outputTokens);
                if (cachedTokens > 0) {
                    ObjectNode details = JSON.createObjectNode();
                    details.put(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS, cachedTokens);
                    respUsage.set(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS, details);
                }
                dst.set(OpenAiResponsesJsonPolicy.FIELD_USAGE, respUsage);
            }

            return dst;
        } catch (Exception e) {
            log.warn("Anthropic→Responses responseToIR error: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    // ========================
    // 流式 SSE 翻译：Anthropic SSE → Responses SSE（IR）
    // ========================

    /**
     * 创建 Anthropic SSE → Responses SSE 流式翻译器。
     */
    public StreamTranslator createStreamToIR(String model) {
        return new AnthropicToIRStreamTranslator(model);
    }

    private static void flushTextBlocks(ArrayNode output, List<JsonNode> textBlocks) {
        if (textBlocks.isEmpty()) return;

        ArrayNode msgContent = JSON.createArrayNode();
        for (JsonNode tb : textBlocks) {
            String text = tb.has(AnthropicMessagesBodyPolicy.FIELD_TEXT)
                    ? tb.get(AnthropicMessagesBodyPolicy.FIELD_TEXT).asText()
                    : "";
            if (text.isEmpty()) {
                continue;
            }
            ObjectNode part = JSON.createObjectNode();
            part.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT);
            part.put(OpenAiResponsesJsonPolicy.FIELD_TEXT, text);
            msgContent.add(part);
        }
        if (msgContent.size() > 0) {
            ObjectNode msgItem = JSON.createObjectNode();
            msgItem.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_MESSAGE);
            msgItem.put(OpenAiResponsesJsonPolicy.FIELD_ROLE, OpenAiResponsesJsonPolicy.ROLE_ASSISTANT);
            msgItem.put(OpenAiResponsesJsonPolicy.FIELD_STATUS, OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
            msgItem.set(OpenAiResponsesJsonPolicy.FIELD_CONTENT, msgContent);
            output.add(msgItem);
        }
        textBlocks.clear();
    }

    // ========================
    // 流式翻译器内部类
    // ========================

    /**
     * Anthropic SSE 事件 → Responses SSE 事件（IR）。
     * <p>
     * 状态机跟踪当前输出 item 类型、sequence_number，将 Anthropic 的
     * content_block_start/delta/stop 事件翻译为对应的 Responses SSE 事件。
     */
    static class AnthropicToIRStreamTranslator implements StreamTranslator {

        private enum ItemType { NONE, MESSAGE, FUNCTION_CALL, REASONING }

        private boolean done = false;
        private boolean createdSent = false;
        private final String responseId;
        private String model;
        private final long createdAt;
        private long sequenceNumber = 0;

        private int outputIndex = -1;
        private ItemType currentItemType = ItemType.NONE;
        private String currentItemId;
        private String currentCallId;
        private String currentName;
        private final StringBuilder currentArguments = new StringBuilder();
        private int contentIndex = 0;
        private boolean messageItemOpen = false;

        private int inputTokens = 0;
        private int outputTokens = 0;
        private int cacheReadInputTokens = 0;

        AnthropicToIRStreamTranslator(String model) {
            this.model = model != null ? model : "unknown";
            this.responseId = "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            this.createdAt = System.currentTimeMillis() / 1000;
        }

        @Override
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (done || line == null || line.isBlank()) return output;
            String json = AnthropicMessagesSsePolicy.extractDataPayload(line);
            if (json == null) return output;

            try {
                JsonNode root = JSON.readTree(json);
                String type = textOrDefault(root.get(AnthropicMessagesBodyPolicy.FIELD_TYPE), null);
                if (type == null) return output;

                switch (type) {
                    case AnthropicMessagesSsePolicy.EVENT_MESSAGE_START -> {
                        if (root.has(AnthropicMessagesBodyPolicy.FIELD_MESSAGE)) {
                            JsonNode msg = root.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGE);
                            if (!isBlankText(msg.get(AnthropicMessagesBodyPolicy.FIELD_MODEL))) {
                                model = msg.get(AnthropicMessagesBodyPolicy.FIELD_MODEL).asText();
                            }
                            if (msg.has(AnthropicMessagesBodyPolicy.FIELD_USAGE)
                                    && isNonNegativeInt(msg.get(AnthropicMessagesBodyPolicy.FIELD_USAGE)
                                    .get(AnthropicMessagesBodyPolicy.FIELD_INPUT_TOKENS))) {
                                inputTokens = msg.get(AnthropicMessagesBodyPolicy.FIELD_USAGE)
                                        .get(AnthropicMessagesBodyPolicy.FIELD_INPUT_TOKENS).asInt();
                            }
                            if (msg.has(AnthropicMessagesBodyPolicy.FIELD_USAGE)
                                    && isNonNegativeInt(msg.get(AnthropicMessagesBodyPolicy.FIELD_USAGE)
                                    .get(AnthropicMessagesBodyPolicy.FIELD_CACHE_READ_INPUT_TOKENS))) {
                                cacheReadInputTokens = msg.get(AnthropicMessagesBodyPolicy.FIELD_USAGE)
                                        .get(AnthropicMessagesBodyPolicy.FIELD_CACHE_READ_INPUT_TOKENS).asInt();
                            }
                        }
                        ensureCreatedSent(output);
                    }
                    case AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_START -> {
                        JsonNode block = root.has(AnthropicMessagesBodyPolicy.FIELD_CONTENT_BLOCK)
                                ? root.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT_BLOCK)
                                : root;
                        String blockType = textOrDefault(block.get(AnthropicMessagesBodyPolicy.FIELD_TYPE),
                                AnthropicMessagesBodyPolicy.TYPE_TEXT);

                        // 关闭上一个 item
                        closeCurrentItem(output);

                        switch (blockType) {
                            case AnthropicMessagesBodyPolicy.TYPE_TEXT -> {
                                if (!messageItemOpen) {
                                    outputIndex++;
                                    currentItemId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                                    currentItemType = ItemType.MESSAGE;
                                    contentIndex = 0;

                                    ensureCreatedSent(output);
                                    appendMessageItemAdded(output);
                                    messageItemOpen = true;
                                }
                                // 多个 text block 复用同一个 message item
                            }
                            case AnthropicThinkingPolicy.TYPE_THINKING -> {
                                outputIndex++;
                                currentItemId = "rsn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                                currentItemType = ItemType.REASONING;

                                ensureCreatedSent(output);
                                appendReasoningItemAdded(output);
                            }
                            case AnthropicMessagesBodyPolicy.TYPE_TOOL_USE -> {
                                if (isBlankText(block.get(AnthropicMessagesBodyPolicy.FIELD_ID))
                                        || isBlankText(block.get(AnthropicMessagesBodyPolicy.FIELD_NAME))) {
                                    log.debug("Anthropic→IR stream: tool_use missing id or name, ignored");
                                    currentItemType = ItemType.NONE;
                                    currentItemId = null;
                                    currentCallId = null;
                                    currentName = null;
                                    currentArguments.setLength(0);
                                    break;
                                }
                                outputIndex++;
                                currentItemId = "item_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
                                currentItemType = ItemType.FUNCTION_CALL;
                                currentCallId = block.get(AnthropicMessagesBodyPolicy.FIELD_ID).asText();
                                currentName = block.get(AnthropicMessagesBodyPolicy.FIELD_NAME).asText();
                                currentArguments.setLength(0);
                                if (block.has(AnthropicMessagesBodyPolicy.FIELD_INPUT)
                                        && !block.get(AnthropicMessagesBodyPolicy.FIELD_INPUT).isNull()
                                        && !(block.get(AnthropicMessagesBodyPolicy.FIELD_INPUT).isObject()
                                        && block.get(AnthropicMessagesBodyPolicy.FIELD_INPUT).size() == 0)) {
                                    currentArguments.append(block.get(AnthropicMessagesBodyPolicy.FIELD_INPUT).toString());
                                }

                                ensureCreatedSent(output);
                                appendFunctionCallItemAdded(output);
                            }
                            case AnthropicMessagesBodyPolicy.TYPE_SERVER_TOOL_USE ->
                                log.debug("Anthropic→IR stream: server_tool_use block ignored");
                            default ->
                                log.debug("Anthropic→IR stream: unknown content_block_start type '{}'", blockType);
                        }
                    }
                    case AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_DELTA -> {
                        JsonNode delta = root.has(AnthropicMessagesBodyPolicy.FIELD_DELTA)
                                ? root.get(AnthropicMessagesBodyPolicy.FIELD_DELTA)
                                : root;
                        String deltaType = textOrDefault(delta.get(AnthropicMessagesBodyPolicy.FIELD_TYPE), "");

                        switch (deltaType) {
                            case AnthropicMessagesBodyPolicy.TYPE_TEXT_DELTA -> {
                                String text = textOrDefault(delta.get(AnthropicMessagesBodyPolicy.FIELD_TEXT), "");
                                if (!text.isEmpty()) {
                                    appendContentDelta(output, OpenAiResponsesSsePolicy.EVENT_OUTPUT_TEXT_DELTA, text);
                                }
                            }
                            case AnthropicThinkingPolicy.TYPE_THINKING_DELTA -> {
                                String thinking = textOrDefault(delta.get(AnthropicThinkingPolicy.FIELD_THINKING), "");
                                if (!thinking.isEmpty()) {
                                    appendReasoningDelta(output, thinking);
                                }
                            }
                            case AnthropicMessagesBodyPolicy.TYPE_INPUT_JSON_DELTA -> {
                                String partialJson = textOrDefault(
                                        delta.get(AnthropicMessagesBodyPolicy.FIELD_PARTIAL_JSON), "");
                                if (!partialJson.isEmpty() && currentItemType == ItemType.FUNCTION_CALL) {
                                    currentArguments.append(partialJson);
                                    appendFunctionArgumentsDelta(output, partialJson);
                                }
                            }
                            case AnthropicMessagesBodyPolicy.TYPE_SIGNATURE_DELTA -> {
                                // Anthropic signatures have no Responses stream equivalent in sub2api.
                            }
                            default -> log.debug("Anthropic→IR stream: unknown delta type '{}'", deltaType);
                        }
                    }
                    case AnthropicMessagesSsePolicy.EVENT_CONTENT_BLOCK_STOP -> {
                        if (currentItemType == ItemType.REASONING) {
                            appendReasoningDone(output);
                            appendSimpleItemDone(output, OpenAiResponsesJsonPolicy.TYPE_REASONING);
                            currentItemType = ItemType.NONE;
                        } else if (currentItemType == ItemType.FUNCTION_CALL) {
                            String arguments = currentArguments.length() > 0
                                    ? currentArguments.toString()
                                    : OpenAiResponsesJsonPolicy.EMPTY_JSON_OBJECT;
                            appendFunctionArgumentsDone(output);
                            appendFunctionCallItemDone(output, arguments);
                            currentItemType = ItemType.NONE;
                            currentArguments.setLength(0);
                        } else if (currentItemType == ItemType.MESSAGE) {
                            // text block stop: 只发送 text.done，不关闭 message item
                            appendTextDone(output);
                        }
                    }
                    case AnthropicMessagesSsePolicy.EVENT_MESSAGE_DELTA -> {
                        if (root.has(AnthropicMessagesBodyPolicy.FIELD_USAGE)
                                && isNonNegativeInt(root.get(AnthropicMessagesBodyPolicy.FIELD_USAGE)
                                .get(AnthropicMessagesBodyPolicy.FIELD_OUTPUT_TOKENS))) {
                            outputTokens = root.get(AnthropicMessagesBodyPolicy.FIELD_USAGE)
                                    .get(AnthropicMessagesBodyPolicy.FIELD_OUTPUT_TOKENS).asInt();
                        }
                        if (root.has(AnthropicMessagesBodyPolicy.FIELD_USAGE)
                                && isNonNegativeInt(root.get(AnthropicMessagesBodyPolicy.FIELD_USAGE)
                                .get(AnthropicMessagesBodyPolicy.FIELD_CACHE_READ_INPUT_TOKENS))) {
                            cacheReadInputTokens = root.get(AnthropicMessagesBodyPolicy.FIELD_USAGE)
                                    .get(AnthropicMessagesBodyPolicy.FIELD_CACHE_READ_INPUT_TOKENS).asInt();
                        }
                    }
                    case AnthropicMessagesSsePolicy.EVENT_MESSAGE_STOP -> {
                        closeCurrentItem(output);
                        appendTerminalResponse(output, OpenAiResponsesSsePolicy.EVENT_RESPONSE_COMPLETED,
                                OpenAiResponsesJsonPolicy.STATUS_COMPLETED, null, null);
                        done = true;
                    }
                    case AnthropicMessagesSsePolicy.EVENT_ERROR -> {
                        JsonNode error = root.path(OpenAiResponsesJsonPolicy.FIELD_ERROR);
                        String errorType = textOrDefault(error.get(OpenAiResponsesJsonPolicy.FIELD_TYPE),
                                AnthropicMessagesSsePolicy.DEFAULT_ERROR_TYPE);
                        String message = textOrDefault(error.get(OpenAiResponsesJsonPolicy.FIELD_MESSAGE),
                                AnthropicMessagesSsePolicy.DEFAULT_ERROR_MESSAGE);
                        ensureCreatedSent(output);
                        appendTerminalResponse(output, OpenAiResponsesSsePolicy.EVENT_RESPONSE_FAILED,
                                OpenAiResponsesJsonPolicy.STATUS_FAILED, errorType, message);
                        done = true;
                    }
                }
            } catch (Exception e) {
                log.debug("Anthropic→IR SSE error: {}", e.getMessage());
            }
            return output;
        }

        private void ensureCreatedSent(List<String> output) {
            if (!createdSent) {
                ObjectNode event = baseResponsesEvent(OpenAiResponsesSsePolicy.EVENT_RESPONSE_CREATED);
                ObjectNode response = responseBase(OpenAiResponsesJsonPolicy.STATUS_IN_PROGRESS);
                response.set(OpenAiResponsesJsonPolicy.FIELD_OUTPUT, JSON.createArrayNode());
                response.putNull(OpenAiResponsesJsonPolicy.FIELD_USAGE);
                event.set(OpenAiResponsesJsonPolicy.FIELD_RESPONSE, response);
                appendJsonEvent(output, OpenAiResponsesSsePolicy.EVENT_RESPONSE_CREATED, event);
                createdSent = true;
            }
        }

        private void closeCurrentItem(List<String> output) {
            if (currentItemType == ItemType.MESSAGE && messageItemOpen) {
                ObjectNode item = responseItem(currentItemId, OpenAiResponsesJsonPolicy.TYPE_MESSAGE,
                        OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
                item.put(OpenAiResponsesJsonPolicy.FIELD_ROLE, OpenAiResponsesJsonPolicy.ROLE_ASSISTANT);
                appendOutputItemDone(output, outputIndex, item);
                messageItemOpen = false;
            }
            // function_call 和 reasoning 在 content_block_stop 中已关闭
        }

        private void appendMessageItemAdded(List<String> output) {
            ObjectNode item = responseItem(currentItemId, OpenAiResponsesJsonPolicy.TYPE_MESSAGE,
                    OpenAiResponsesJsonPolicy.STATUS_IN_PROGRESS);
            item.put(OpenAiResponsesJsonPolicy.FIELD_ROLE, OpenAiResponsesJsonPolicy.ROLE_ASSISTANT);
            item.set(OpenAiResponsesJsonPolicy.FIELD_CONTENT, JSON.createArrayNode());
            appendOutputItemAdded(output, item);
        }

        private void appendReasoningItemAdded(List<String> output) {
            ObjectNode item = responseItem(currentItemId, OpenAiResponsesJsonPolicy.TYPE_REASONING,
                    OpenAiResponsesJsonPolicy.STATUS_IN_PROGRESS);
            item.set(OpenAiResponsesJsonPolicy.FIELD_SUMMARY, JSON.createArrayNode());
            appendOutputItemAdded(output, item);
        }

        private void appendFunctionCallItemAdded(List<String> output) {
            ObjectNode item = responseItem(currentItemId, OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL,
                    OpenAiResponsesJsonPolicy.STATUS_IN_PROGRESS);
            item.put(OpenAiResponsesJsonPolicy.FIELD_CALL_ID, currentCallId);
            item.put(OpenAiResponsesJsonPolicy.FIELD_NAME, currentName);
            appendOutputItemAdded(output, item);
        }

        private void appendOutputItemAdded(List<String> output, ObjectNode item) {
            ObjectNode event = baseResponseIdEvent(OpenAiResponsesSsePolicy.EVENT_OUTPUT_ITEM_ADDED);
            event.put(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX, outputIndex);
            event.set(OpenAiResponsesJsonPolicy.FIELD_ITEM, item);
            appendJsonEvent(output, OpenAiResponsesSsePolicy.EVENT_OUTPUT_ITEM_ADDED, event);
        }

        private void appendOutputItemDone(List<String> output, int itemOutputIndex, ObjectNode item) {
            ObjectNode event = baseResponseIdEvent(OpenAiResponsesSsePolicy.EVENT_OUTPUT_ITEM_DONE);
            event.put(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX, itemOutputIndex);
            event.set(OpenAiResponsesJsonPolicy.FIELD_ITEM, item);
            appendJsonEvent(output, OpenAiResponsesSsePolicy.EVENT_OUTPUT_ITEM_DONE, event);
        }

        private void appendContentDelta(List<String> output, String eventType, String delta) {
            ObjectNode event = baseItemEvent(eventType);
            event.put(OpenAiResponsesJsonPolicy.FIELD_CONTENT_INDEX, contentIndex);
            event.put(OpenAiResponsesJsonPolicy.FIELD_DELTA, delta);
            appendJsonEvent(output, eventType, event);
        }

        private void appendReasoningDelta(List<String> output, String delta) {
            ObjectNode event = baseItemEvent(OpenAiResponsesSsePolicy.EVENT_REASONING_SUMMARY_TEXT_DELTA);
            event.put(OpenAiResponsesJsonPolicy.FIELD_SUMMARY_INDEX, 0);
            event.put(OpenAiResponsesJsonPolicy.FIELD_DELTA, delta);
            appendJsonEvent(output, OpenAiResponsesSsePolicy.EVENT_REASONING_SUMMARY_TEXT_DELTA, event);
        }

        private void appendReasoningDone(List<String> output) {
            ObjectNode event = baseItemEvent(OpenAiResponsesSsePolicy.EVENT_REASONING_SUMMARY_TEXT_DONE);
            event.put(OpenAiResponsesJsonPolicy.FIELD_SUMMARY_INDEX, 0);
            appendJsonEvent(output, OpenAiResponsesSsePolicy.EVENT_REASONING_SUMMARY_TEXT_DONE, event);
        }

        private void appendTextDone(List<String> output) {
            ObjectNode event = baseItemEvent(OpenAiResponsesSsePolicy.EVENT_OUTPUT_TEXT_DONE);
            event.put(OpenAiResponsesJsonPolicy.FIELD_CONTENT_INDEX, contentIndex);
            appendJsonEvent(output, OpenAiResponsesSsePolicy.EVENT_OUTPUT_TEXT_DONE, event);
        }

        private void appendFunctionArgumentsDelta(List<String> output, String delta) {
            ObjectNode event = baseItemEvent(OpenAiResponsesSsePolicy.EVENT_FUNCTION_CALL_ARGUMENTS_DELTA);
            event.put(OpenAiResponsesJsonPolicy.FIELD_DELTA, delta);
            appendJsonEvent(output, OpenAiResponsesSsePolicy.EVENT_FUNCTION_CALL_ARGUMENTS_DELTA, event);
        }

        private void appendFunctionArgumentsDone(List<String> output) {
            ObjectNode event = baseItemEvent(OpenAiResponsesSsePolicy.EVENT_FUNCTION_CALL_ARGUMENTS_DONE);
            appendJsonEvent(output, OpenAiResponsesSsePolicy.EVENT_FUNCTION_CALL_ARGUMENTS_DONE, event);
        }

        private void appendFunctionCallItemDone(List<String> output, String arguments) {
            ObjectNode item = responseItem(currentItemId, OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL,
                    OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
            item.put(OpenAiResponsesJsonPolicy.FIELD_CALL_ID, currentCallId);
            item.put(OpenAiResponsesJsonPolicy.FIELD_NAME, currentName);
            item.put(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS, arguments);
            appendOutputItemDone(output, outputIndex, item);
        }

        private void appendSimpleItemDone(List<String> output, String itemType) {
            ObjectNode item = responseItem(currentItemId, itemType, OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
            appendOutputItemDone(output, outputIndex, item);
        }

        private void appendTerminalResponse(List<String> output, String eventType, String status,
                                            String errorCode, String errorMessage) {
            ObjectNode event = baseResponsesEvent(eventType);
            ObjectNode response = responseBase(status);
            response.set(OpenAiResponsesJsonPolicy.FIELD_USAGE, responseUsage());
            if (errorCode != null || errorMessage != null) {
                ObjectNode error = JSON.createObjectNode();
                error.put(OpenAiResponsesJsonPolicy.FIELD_CODE,
                        errorCode != null ? errorCode : AnthropicMessagesSsePolicy.DEFAULT_ERROR_TYPE);
                error.put(OpenAiResponsesJsonPolicy.FIELD_MESSAGE,
                        errorMessage != null ? errorMessage : AnthropicMessagesSsePolicy.DEFAULT_ERROR_MESSAGE);
                response.set(OpenAiResponsesJsonPolicy.FIELD_ERROR, error);
            }
            event.set(OpenAiResponsesJsonPolicy.FIELD_RESPONSE, response);
            appendJsonEvent(output, eventType, event);
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

        private ObjectNode baseItemEvent(String eventType) {
            ObjectNode event = baseResponseIdEvent(eventType);
            event.put(OpenAiResponsesJsonPolicy.FIELD_ITEM_ID, currentItemId);
            event.put(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX, outputIndex);
            return event;
        }

        private ObjectNode responseBase(String status) {
            ObjectNode response = JSON.createObjectNode();
            response.put(OpenAiResponsesJsonPolicy.FIELD_ID, responseId);
            response.put(OpenAiResponsesJsonPolicy.FIELD_OBJECT, OpenAiResponsesJsonPolicy.OBJECT_RESPONSE);
            response.put(OpenAiResponsesJsonPolicy.FIELD_MODEL, model);
            response.put(OpenAiResponsesJsonPolicy.FIELD_CREATED_AT, createdAt);
            response.put(OpenAiResponsesJsonPolicy.FIELD_STATUS, status);
            return response;
        }

        private ObjectNode responseUsage() {
            ObjectNode usage = JSON.createObjectNode();
            usage.put(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS, inputTokens);
            usage.put(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_TOKENS, outputTokens);
            usage.put(OpenAiResponsesJsonPolicy.FIELD_TOTAL_TOKENS, inputTokens + outputTokens);
            if (cacheReadInputTokens > 0) {
                ObjectNode details = JSON.createObjectNode();
                details.put(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS, cacheReadInputTokens);
                usage.set(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS, details);
            }
            return usage;
        }

        private ObjectNode responseItem(String itemId, String type, String status) {
            ObjectNode item = JSON.createObjectNode();
            item.put(OpenAiResponsesJsonPolicy.FIELD_ID, itemId);
            item.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, type);
            item.put(OpenAiResponsesJsonPolicy.FIELD_STATUS, status);
            return item;
        }

        private void appendJsonEvent(List<String> output, String eventType, ObjectNode event) {
            appendEvent(output, eventType, event.toString());
        }

        @Override public boolean isDone() { return done; }
        @Override public int getInputTokens() { return inputTokens; }
        @Override public int getOutputTokens() { return outputTokens; }
    }

    // ========================
    // 辅助方法
    // ========================

    /**
     * 提取 Anthropic system 字段中的纯文本列表。
     * 过滤 x-anthropic-billing-header: 前缀的文本和空文本。
     */
    private static List<String> extractSystemTexts(JsonNode system) {
        List<String> texts = new ArrayList<>();
        if (system == null) return texts;

        if (system.isTextual()) {
            String text = system.asText();
            if (!text.isEmpty() && !AnthropicClaudeCodeProfile.isBillingHeaderText(text)) {
                texts.add(text);
            }
            return texts;
        }

        if (system.isArray()) {
            for (JsonNode block : system) {
                String blockType = textOrDefault(block.get(AnthropicMessagesBodyPolicy.FIELD_TYPE), "");
                if (AnthropicMessagesBodyPolicy.TYPE_TEXT.equals(blockType)
                        && block.has(AnthropicMessagesBodyPolicy.FIELD_TEXT)) {
                    String text = textOrDefault(block.get(AnthropicMessagesBodyPolicy.FIELD_TEXT), "");
                    if (!text.isEmpty() && !AnthropicClaudeCodeProfile.isBillingHeaderText(text)) {
                        texts.add(text);
                    }
                }
            }
        }
        return texts;
    }

    /**
     * 转换 Anthropic user 消息到 Responses input items。
     * <p>
     * tool_result 块独立产出 function_call_output items；
         * text + image + document 块产出 user message item。
     */
    private static void convertUserMessage(JsonNode contentNode, ArrayNode input) {
        if (contentNode == null) return;

        // 纯字符串
        if (contentNode.isTextual()) {
            ObjectNode item = JSON.createObjectNode();
            item.put(OpenAiResponsesBodyPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TYPE_MESSAGE);
            item.put(OpenAiResponsesBodyPolicy.FIELD_ROLE, OpenAiResponsesBodyPolicy.ROLE_USER);
            ArrayNode parts = JSON.createArrayNode();
            ObjectNode part = JSON.createObjectNode();
            part.put(OpenAiResponsesBodyPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TYPE_INPUT_TEXT);
            part.put(OpenAiResponsesBodyPolicy.FIELD_TEXT, contentNode.asText());
            parts.add(part);
            item.set(OpenAiResponsesBodyPolicy.FIELD_CONTENT, parts);
            input.add(item);
            return;
        }

        if (!contentNode.isArray()) return;

        List<JsonNode> toolResults = new ArrayList<>();
        List<JsonNode> textBlocks = new ArrayList<>();
        List<JsonNode> imageBlocks = new ArrayList<>();
        List<JsonNode> documentBlocks = new ArrayList<>();

        for (JsonNode block : contentNode) {
            String blockType = textOrDefault(block.get(AnthropicMessagesBodyPolicy.FIELD_TYPE),
                    AnthropicMessagesBodyPolicy.TYPE_TEXT);
            switch (blockType) {
                case AnthropicMessagesBodyPolicy.TYPE_TOOL_RESULT -> toolResults.add(block);
                case AnthropicMessagesBodyPolicy.TYPE_TEXT -> textBlocks.add(block);
                case AnthropicMessagesBodyPolicy.TYPE_IMAGE -> imageBlocks.add(block);
                case AnthropicMessagesBodyPolicy.TYPE_DOCUMENT -> documentBlocks.add(block);
                default -> log.debug("Anthropic→Responses: unknown user block type '{}'", blockType);
            }
        }

        // tool_result → function_call_output items
        List<JsonNode> deferredImages = new ArrayList<>();
        List<JsonNode> deferredDocuments = new ArrayList<>();
        for (JsonNode tr : toolResults) {
            if (isBlankText(tr.get(AnthropicMessagesBodyPolicy.FIELD_TOOL_USE_ID))) {
                log.debug("Anthropic→Responses: tool_result missing tool_use_id, ignored");
                continue;
            }
            ObjectNode fco = JSON.createObjectNode();
            fco.put(OpenAiResponsesBodyPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TYPE_FUNCTION_CALL_OUTPUT);
            fco.put(OpenAiResponsesBodyPolicy.FIELD_CALL_ID,
                    GatewayToolCallIdPolicy.toResponsesCallIdFromAnthropic(
                            tr.get(AnthropicMessagesBodyPolicy.FIELD_TOOL_USE_ID).asText()));

            // 提取文本，图片和文档作为后续 user content 保留。
            JsonNode trContent = tr.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT);
            if (trContent != null && trContent.isArray()) {
                StringBuilder textOutput = new StringBuilder();
                for (JsonNode c : trContent) {
                    String cType = textOrDefault(c.get(AnthropicMessagesBodyPolicy.FIELD_TYPE),
                            AnthropicMessagesBodyPolicy.TYPE_TEXT);
                    String text = textOrDefault(c.get(AnthropicMessagesBodyPolicy.FIELD_TEXT), "");
                    if (AnthropicMessagesBodyPolicy.TYPE_TEXT.equals(cType) && !text.isEmpty()) {
                        if (textOutput.length() > 0) textOutput.append("\n");
                        textOutput.append(text);
                    } else if (AnthropicMessagesBodyPolicy.TYPE_IMAGE.equals(cType)) {
                        deferredImages.add(c);
                    } else if (AnthropicMessagesBodyPolicy.TYPE_DOCUMENT.equals(cType)) {
                        deferredDocuments.add(c);
                    }
                }
                fco.put(OpenAiResponsesJsonPolicy.FIELD_OUTPUT, textOutput.toString());
            } else if (trContent != null && trContent.isTextual()) {
                String output = trContent.asText();
                fco.put(OpenAiResponsesJsonPolicy.FIELD_OUTPUT,
                        output.isEmpty() ? OpenAiResponsesBodyPolicy.DEFAULT_EMPTY_TOOL_OUTPUT : output);
            } else {
                fco.put(OpenAiResponsesJsonPolicy.FIELD_OUTPUT,
                        OpenAiResponsesBodyPolicy.DEFAULT_EMPTY_TOOL_OUTPUT);
            }
            if (fco.path(OpenAiResponsesJsonPolicy.FIELD_OUTPUT).asText("").isEmpty()) {
                fco.put(OpenAiResponsesJsonPolicy.FIELD_OUTPUT,
                        OpenAiResponsesBodyPolicy.DEFAULT_EMPTY_TOOL_OUTPUT);
            }
            input.add(fco);
        }

        // text + image + 延迟图片 → user message item
        List<JsonNode> allParts = new ArrayList<>();
        allParts.addAll(textBlocks);
        allParts.addAll(imageBlocks);
        allParts.addAll(documentBlocks);
        allParts.addAll(deferredImages);
        allParts.addAll(deferredDocuments);

        if (!allParts.isEmpty()) {
            ObjectNode item = JSON.createObjectNode();
            item.put(OpenAiResponsesBodyPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TYPE_MESSAGE);
            item.put(OpenAiResponsesBodyPolicy.FIELD_ROLE, OpenAiResponsesBodyPolicy.ROLE_USER);
            ArrayNode parts = JSON.createArrayNode();
            for (JsonNode p : allParts) {
                String pType = textOrDefault(p.get(AnthropicMessagesBodyPolicy.FIELD_TYPE),
                        AnthropicMessagesBodyPolicy.TYPE_TEXT);
                if (AnthropicMessagesBodyPolicy.TYPE_TEXT.equals(pType)) {
                    String text = textOrDefault(p.get(AnthropicMessagesBodyPolicy.FIELD_TEXT), "");
                    if (text.isEmpty()) {
                        continue;
                    }
                    ObjectNode part = JSON.createObjectNode();
                    part.put(OpenAiResponsesBodyPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TYPE_INPUT_TEXT);
                    part.put(OpenAiResponsesBodyPolicy.FIELD_TEXT, text);
                    parts.add(part);
                } else if (AnthropicMessagesBodyPolicy.TYPE_IMAGE.equals(pType)) {
                    String dataUri = anthropicImageToDataURI(p);
                    if (dataUri != null && !dataUri.isEmpty()) {
                        ObjectNode part = JSON.createObjectNode();
                        part.put(OpenAiResponsesBodyPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TYPE_INPUT_IMAGE);
                        part.put(OpenAiResponsesBodyPolicy.FIELD_IMAGE_URL, dataUri);
                        parts.add(part);
                    }
                } else if (AnthropicMessagesBodyPolicy.TYPE_DOCUMENT.equals(pType)) {
                    ObjectNode part = anthropicDocumentToResponsesInputFile(p);
                    if (part != null) {
                        parts.add(part);
                    }
                }
            }
            if (parts.size() > 0) {
                item.set(OpenAiResponsesBodyPolicy.FIELD_CONTENT, parts);
                input.add(item);
            }
        }
    }

    /**
     * Anthropic document content block → Responses input_file content part.
     * Only source forms with an OpenAI Responses input_file equivalent are converted.
     */
    private static ObjectNode anthropicDocumentToResponsesInputFile(JsonNode block) {
        if (block == null
                || !block.has(AnthropicMessagesBodyPolicy.FIELD_SOURCE)
                || !block.get(AnthropicMessagesBodyPolicy.FIELD_SOURCE).isObject()) return null;
        JsonNode source = block.get(AnthropicMessagesBodyPolicy.FIELD_SOURCE);
        String sourceType = textOrDefault(source.get(AnthropicMessagesBodyPolicy.FIELD_TYPE), "");
        ObjectNode part = JSON.createObjectNode();
        part.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_INPUT_FILE);

        if (!isBlankText(block.get(AnthropicMessagesBodyPolicy.FIELD_TITLE))) {
            part.put(OpenAiResponsesJsonPolicy.FIELD_FILENAME,
                    block.get(AnthropicMessagesBodyPolicy.FIELD_TITLE).asText());
        } else if (!isBlankText(source.get(AnthropicMessagesBodyPolicy.FIELD_FILENAME))) {
            part.put(OpenAiResponsesJsonPolicy.FIELD_FILENAME,
                    source.get(AnthropicMessagesBodyPolicy.FIELD_FILENAME).asText());
        }

        switch (sourceType) {
            case AnthropicMessagesBodyPolicy.TYPE_URL -> {
                if (isBlankText(source.get(AnthropicMessagesBodyPolicy.FIELD_URL))) return null;
                part.put(OpenAiResponsesJsonPolicy.FIELD_FILE_URL,
                        source.get(AnthropicMessagesBodyPolicy.FIELD_URL).asText());
            }
            case AnthropicMessagesBodyPolicy.TYPE_BASE64 -> {
                if (isBlankText(source.get(AnthropicMessagesBodyPolicy.FIELD_DATA))) return null;
                String mediaType = !isBlankText(source.get(AnthropicMessagesBodyPolicy.FIELD_MEDIA_TYPE))
                        ? source.get(AnthropicMessagesBodyPolicy.FIELD_MEDIA_TYPE).asText()
                        : OpenAiResponsesBodyPolicy.DEFAULT_DOCUMENT_MEDIA_TYPE;
                part.put(OpenAiResponsesJsonPolicy.FIELD_FILE_DATA,
                        OpenAiResponsesBodyPolicy.base64DataUri(mediaType,
                                source.get(AnthropicMessagesBodyPolicy.FIELD_DATA).asText()));
            }
            case AnthropicMessagesBodyPolicy.TYPE_FILE -> {
                if (isBlankText(source.get(AnthropicMessagesBodyPolicy.FIELD_FILE_ID))) return null;
                part.put(OpenAiResponsesJsonPolicy.FIELD_FILE_ID,
                        source.get(AnthropicMessagesBodyPolicy.FIELD_FILE_ID).asText());
            }
            default -> {
                log.debug("Anthropic→Responses: unsupported document source type '{}'", sourceType);
                return null;
            }
        }
        return part;
    }

    /**
     * 转换 Anthropic assistant 消息到 Responses input items。
     * <p>
     * text 块 → message item；tool_use 块 → function_call item；
     * thinking/redacted_thinking 块 → 请求续链时不重放。对齐 sub2api：
     * OpenAI Responses/Codex input 不接受 Anthropic thinking 历史块作为输入。
     */
    private static void convertAssistantMessage(JsonNode contentNode, ArrayNode input) {
        if (contentNode == null) return;

        if (contentNode.isTextual()) {
            ObjectNode item = JSON.createObjectNode();
            item.put(OpenAiResponsesBodyPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TYPE_MESSAGE);
            item.put(OpenAiResponsesBodyPolicy.FIELD_ROLE, OpenAiResponsesBodyPolicy.ROLE_ASSISTANT);
            ArrayNode parts = JSON.createArrayNode();
            ObjectNode part = JSON.createObjectNode();
            part.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT);
            part.put(OpenAiResponsesJsonPolicy.FIELD_TEXT, contentNode.asText());
            parts.add(part);
            item.set(OpenAiResponsesBodyPolicy.FIELD_CONTENT, parts);
            input.add(item);
            return;
        }

        if (!contentNode.isArray()) return;

        // 收集所有 text 块文本
        StringBuilder textBuilder = new StringBuilder();
        List<JsonNode> toolUses = new ArrayList<>();

        for (JsonNode block : contentNode) {
            String blockType = textOrDefault(block.get(AnthropicMessagesBodyPolicy.FIELD_TYPE),
                    AnthropicMessagesBodyPolicy.TYPE_TEXT);
            switch (blockType) {
                case AnthropicMessagesBodyPolicy.TYPE_TEXT -> {
                    String text = textOrDefault(block.get(AnthropicMessagesBodyPolicy.FIELD_TEXT), "");
                    if (!text.isEmpty()) {
                        if (textBuilder.length() > 0) textBuilder.append("\n\n");
                        textBuilder.append(text);
                    }
                }
                case AnthropicMessagesBodyPolicy.TYPE_TOOL_USE -> toolUses.add(block);
                case AnthropicThinkingPolicy.TYPE_THINKING, AnthropicThinkingPolicy.TYPE_REDACTED_THINKING ->
                        log.debug("Anthropic→Responses request: assistant {} block ignored", blockType);
                default -> log.debug("Anthropic→Responses: unknown assistant block type '{}'", blockType);
            }
        }

        // 非空文本 → message item
        String text = textBuilder.toString();
        if (!text.isEmpty()) {
            ObjectNode item = JSON.createObjectNode();
            item.put(OpenAiResponsesBodyPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TYPE_MESSAGE);
            item.put(OpenAiResponsesBodyPolicy.FIELD_ROLE, OpenAiResponsesBodyPolicy.ROLE_ASSISTANT);
            ArrayNode parts = JSON.createArrayNode();
            ObjectNode part = JSON.createObjectNode();
            part.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT);
            part.put(OpenAiResponsesJsonPolicy.FIELD_TEXT, text);
            parts.add(part);
            item.set(OpenAiResponsesBodyPolicy.FIELD_CONTENT, parts);
            input.add(item);
        }

        // tool_use → function_call items
        for (JsonNode tu : toolUses) {
            if (isBlankText(tu.get(AnthropicMessagesBodyPolicy.FIELD_ID))
                    || isBlankText(tu.get(AnthropicMessagesBodyPolicy.FIELD_NAME))) {
                log.debug("Anthropic→Responses: assistant tool_use missing id or name, ignored");
                continue;
            }
            ObjectNode fco = JSON.createObjectNode();
            fco.put(OpenAiResponsesBodyPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TYPE_FUNCTION_CALL);
            fco.put(OpenAiResponsesBodyPolicy.FIELD_CALL_ID,
                    GatewayToolCallIdPolicy.toResponsesCallIdFromAnthropic(
                            tu.get(AnthropicMessagesBodyPolicy.FIELD_ID).asText()));
            fco.put(OpenAiResponsesJsonPolicy.FIELD_NAME, tu.get(AnthropicMessagesBodyPolicy.FIELD_NAME).asText());
            fco.put(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS,
                    tu.has(AnthropicMessagesBodyPolicy.FIELD_INPUT)
                            ? tu.get(AnthropicMessagesBodyPolicy.FIELD_INPUT).toString()
                            : OpenAiResponsesJsonPolicy.EMPTY_JSON_OBJECT);
            input.add(fco);
        }
    }

    /**
     * 转换 Anthropic tool 到 Responses tool。
     */
    private static JsonNode convertAnthropicToolToResponses(JsonNode tool) {
        String toolType = textOrDefault(tool.get(AnthropicMessagesBodyPolicy.FIELD_TYPE), "");
        // web_search_ 前缀 → web_search
        if (GatewayWebSearchToolPolicy.isAnthropicServerWebSearchToolType(toolType)) {
            ObjectNode ws = JSON.createObjectNode();
            ws.put(OpenAiResponsesBodyPolicy.FIELD_TYPE, GatewayWebSearchToolPolicy.TYPE_WEB_SEARCH);
            ObjectNode userLocation = normalizeWebSearchUserLocation(
                    tool.get(OpenAiResponsesBodyPolicy.FIELD_USER_LOCATION));
            if (userLocation != null) {
                ws.set(OpenAiResponsesBodyPolicy.FIELD_USER_LOCATION, userLocation);
            }
            return ws;
        }
        if (isBlankText(tool.get(AnthropicMessagesBodyPolicy.FIELD_NAME))) {
            return null;
        }
        // 普通 function tool
        ObjectNode func = JSON.createObjectNode();
        func.put(OpenAiResponsesBodyPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TOOL_CHOICE_FUNCTION);
        func.put(OpenAiResponsesJsonPolicy.FIELD_NAME, tool.get(AnthropicMessagesBodyPolicy.FIELD_NAME).asText());
        copyTextIfExists(tool, func, AnthropicMessagesBodyPolicy.FIELD_DESCRIPTION);
        // input_schema → parameters（规范化）
        JsonNode schema = tool.has(AnthropicMessagesBodyPolicy.FIELD_INPUT_SCHEMA)
                ? tool.get(AnthropicMessagesBodyPolicy.FIELD_INPUT_SCHEMA)
                : null;
        func.set(OpenAiResponsesBodyPolicy.FIELD_PARAMETERS, normalizeToolParameters(schema));
        func.put(OpenAiResponsesBodyPolicy.FIELD_STRICT, false);
        return func;
    }

    /**
     * 规范化 tool parameters schema —— 空/null → {"type":"object","properties":{}}。
     */
    private static JsonNode normalizeToolParameters(JsonNode schema) {
        if (schema == null || schema.isNull() || (schema.isTextual() && "null".equals(schema.asText()))) {
            return createEmptySchema();
        }
        if (schema.isObject()) {
            ObjectNode obj = schema.deepCopy();
            if (OpenAiResponsesJsonPolicy.TYPE_OBJECT.equals(
                    textOrDefault(obj.get(AnthropicMessagesBodyPolicy.FIELD_TYPE), ""))
                    && !obj.has(OpenAiResponsesJsonPolicy.FIELD_PROPERTIES)) {
                obj.set(OpenAiResponsesJsonPolicy.FIELD_PROPERTIES, JSON.createObjectNode());
            }
            return obj;
        }
        return schema.deepCopy();
    }

    private static ObjectNode createEmptySchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_OBJECT);
        schema.set(OpenAiResponsesJsonPolicy.FIELD_PROPERTIES, JSON.createObjectNode());
        return schema;
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

    /**
     * 转换 Anthropic tool_choice 到 Responses tool_choice。
     */
    private static JsonNode convertAnthropicToolChoiceToResponses(JsonNode toolChoice) {
        if (toolChoice.isTextual()) {
            return switch (toolChoice.asText()) {
                case AnthropicMessagesBodyPolicy.TYPE_AUTO ->
                        JSON.getNodeFactory().textNode(OpenAiResponsesBodyPolicy.TOOL_CHOICE_AUTO);
                case AnthropicMessagesBodyPolicy.TYPE_ANY ->
                        JSON.getNodeFactory().textNode(OpenAiResponsesBodyPolicy.TOOL_CHOICE_REQUIRED);
                case OpenAiResponsesBodyPolicy.TOOL_CHOICE_NONE ->
                        JSON.getNodeFactory().textNode(OpenAiResponsesBodyPolicy.TOOL_CHOICE_NONE);
                default -> toolChoice.deepCopy();
            };
        }

        if (toolChoice.isObject() && toolChoice.has(AnthropicMessagesBodyPolicy.FIELD_TYPE)) {
            String tcType = textOrDefault(toolChoice.get(AnthropicMessagesBodyPolicy.FIELD_TYPE), "");
            return switch (tcType) {
                case AnthropicMessagesBodyPolicy.TYPE_AUTO ->
                        JSON.getNodeFactory().textNode(OpenAiResponsesBodyPolicy.TOOL_CHOICE_AUTO);
                case AnthropicMessagesBodyPolicy.TYPE_ANY ->
                        JSON.getNodeFactory().textNode(OpenAiResponsesBodyPolicy.TOOL_CHOICE_REQUIRED);
                case OpenAiResponsesBodyPolicy.TOOL_CHOICE_NONE ->
                        JSON.getNodeFactory().textNode(OpenAiResponsesBodyPolicy.TOOL_CHOICE_NONE);
                case AnthropicMessagesBodyPolicy.TYPE_TOOL_CHOICE_TOOL -> {
                    if (isBlankText(toolChoice.get(AnthropicMessagesBodyPolicy.FIELD_NAME))) {
                        yield toolChoice.deepCopy();
                    }
                    ObjectNode obj = JSON.createObjectNode();
                    obj.put(OpenAiResponsesBodyPolicy.FIELD_TYPE, OpenAiResponsesBodyPolicy.TOOL_CHOICE_FUNCTION);
                    obj.put(OpenAiResponsesJsonPolicy.FIELD_NAME,
                            toolChoice.get(AnthropicMessagesBodyPolicy.FIELD_NAME).asText());
                    yield obj;
                }
                default -> toolChoice.deepCopy();
            };
        }
        return toolChoice.isNull() ? null : toolChoice.deepCopy();
    }

    private static boolean isBlankText(JsonNode node) {
        return node == null || !node.isTextual() || node.asText().isBlank();
    }

    private static String textOrDefault(JsonNode node, String defaultValue) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : defaultValue;
    }

    private static ObjectNode convertAnthropicThinkingToResponsesReasoning(JsonNode block) {
        ObjectNode reasoningItem = JSON.createObjectNode();
        reasoningItem.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_REASONING);
        reasoningItem.put(OpenAiResponsesJsonPolicy.FIELD_STATUS, OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
        String thinking = textOrDefault(block.get(AnthropicThinkingPolicy.FIELD_THINKING), "");

        if (!thinking.isEmpty()) {
            ArrayNode summary = JSON.createArrayNode();
            ObjectNode summaryText = JSON.createObjectNode();
            summaryText.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_SUMMARY_TEXT);
            summaryText.put(OpenAiResponsesJsonPolicy.FIELD_TEXT, thinking);
            summary.add(summaryText);
            reasoningItem.set(OpenAiResponsesJsonPolicy.FIELD_SUMMARY, summary);
        }
        return reasoningItem;
    }

    private static ObjectNode convertAnthropicRedactedThinkingToResponsesReasoning(JsonNode block) {
        ObjectNode reasoningItem = JSON.createObjectNode();
        reasoningItem.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, OpenAiResponsesJsonPolicy.TYPE_REASONING);
        reasoningItem.put(OpenAiResponsesJsonPolicy.FIELD_STATUS, OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
        return reasoningItem;
    }

    private static boolean hasReasoningPayload(JsonNode reasoningItem) {
        return reasoningItem.has(OpenAiResponsesJsonPolicy.FIELD_CONTENT)
                || reasoningItem.has(OpenAiResponsesJsonPolicy.FIELD_SUMMARY)
                || reasoningItem.has("encrypted_content");
    }

    /**
     * Anthropic effort → Responses effort 映射。
     * low/medium/high 保持同名，xhigh/max→xhigh；未知值不透传。
     */
    private static String mapAnthropicEffortToResponses(JsonNode effort) {
        if (effort == null || !effort.isTextual()) return null;
        return switch (effort.asText()) {
            case "low" -> "low";
            case "medium" -> "medium";
            case "high" -> "high";
            case "xhigh", "max" -> "xhigh";
            default -> effort.asText();
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
     * Anthropic stop_reason → Responses status。
     * max_tokens/model_context_window_exceeded→"incomplete", 其他→"completed"。
     * 只有 max_tokens 有 OpenAI Responses 明确等价的 incomplete_details.reason。
     */
    private static String mapAnthropicStopReasonToResponsesStatus(String stopReason) {
        return switch (stopReason) {
            case AnthropicMessagesBodyPolicy.STOP_REASON_MAX_TOKENS,
                    AnthropicMessagesBodyPolicy.STOP_REASON_MODEL_CONTEXT_WINDOW_EXCEEDED ->
                    OpenAiResponsesJsonPolicy.STATUS_INCOMPLETE;
            default -> OpenAiResponsesJsonPolicy.STATUS_COMPLETED;
        };
    }

    /**
     * Anthropic image source → data URI 字符串。
     */
    private static String anthropicImageToDataURI(JsonNode imageBlock) {
        if (imageBlock == null || !imageBlock.has(AnthropicMessagesBodyPolicy.FIELD_SOURCE)) return null;
        JsonNode source = imageBlock.get(AnthropicMessagesBodyPolicy.FIELD_SOURCE);
        String sourceType = textOrDefault(source.get(AnthropicMessagesBodyPolicy.FIELD_TYPE), "");
        if (AnthropicMessagesBodyPolicy.TYPE_URL.equals(sourceType)) {
            return textOrDefault(source.get(AnthropicMessagesBodyPolicy.FIELD_URL), null);
        }
        String mediaType = textOrDefault(source.get(AnthropicMessagesBodyPolicy.FIELD_MEDIA_TYPE),
                OpenAiResponsesBodyPolicy.DEFAULT_IMAGE_MEDIA_TYPE);
        String data = textOrDefault(source.get(AnthropicMessagesBodyPolicy.FIELD_DATA), "");
        if (data.isEmpty()) return null;
        return OpenAiResponsesBodyPolicy.base64DataUri(mediaType, data);
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

    private static void appendEvent(List<String> output, String event, String data) {
        output.add(OpenAiResponsesSsePolicy.EVENT_LINE_PREFIX + event);
        output.add(OpenAiResponsesSsePolicy.DATA_LINE_PREFIX + data);
        output.add(OpenAiResponsesSsePolicy.FRAME_SEPARATOR_LINE);
    }
}
