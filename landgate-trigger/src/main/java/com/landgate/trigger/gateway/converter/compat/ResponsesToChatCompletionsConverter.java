package com.landgate.trigger.gateway.converter.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.trigger.gateway.converter.StreamTranslator;
import com.landgate.types.gateway.GatewayProtocolIrPolicy;
import com.landgate.types.gateway.GatewayWebSearchToolPolicy;
import com.landgate.types.gateway.OpenAiChatCompletionsBodyPolicy;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;
import com.landgate.types.gateway.OpenAiResponsesJsonPolicy;
import com.landgate.types.gateway.OpenAiResponsesSsePolicy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.landgate.types.gateway.OpenAiChatCompletionsBodyPolicy.*;

/**
 * OpenAI Responses API（IR）→ OpenAI Chat Completions API 转换器。
 * <p>
 * 负责请求反向转换、非流式响应反向转换、流式 SSE 反向翻译。
 * 由 {@link ChatCompletionsConverter} 门面委托调用，不作为独立 Spring Bean。
 *
 * <p>参照：sub2api {@code responses_to_chatcompletions.go} + {@code chatcompletions_responses_bridge.go}
 */
@Slf4j
public class ResponsesToChatCompletionsConverter {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ========================
    // 请求反向转换：Responses IR → Chat Completions
    // ========================

    /**
     * 将 Responses API 请求（IR）转换为 Chat Completions 请求。
     */
    public String requestFromIR(JsonNode ir) {
        try {
            ObjectNode dst = JSON.createObjectNode();

            copyTextIfExists(ir, dst, FIELD_MODEL);
            copyNumberIfExists(ir, dst, OpenAiResponsesBodyPolicy.FIELD_TEMPERATURE);
            copyNumberIfExists(ir, dst, OpenAiResponsesBodyPolicy.FIELD_TOP_P);
            copyTextIfExists(ir, dst, OpenAiResponsesBodyPolicy.FIELD_SERVICE_TIER);
            copyObjectIfExists(ir, dst, OpenAiResponsesBodyPolicy.FIELD_METADATA);
            copyBooleanIfExists(ir, dst, OpenAiResponsesBodyPolicy.FIELD_PARALLEL_TOOL_CALLS);
            copyTextIfExists(ir, dst, OpenAiResponsesBodyPolicy.FIELD_USER);
            copyBooleanIfExists(ir, dst, OpenAiResponsesBodyPolicy.FIELD_STORE);
            copyTextIfExists(ir, dst, OpenAiResponsesBodyPolicy.FIELD_SAFETY_IDENTIFIER);
            copyTextIfExists(ir, dst, OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_KEY);
            copyTextIfExists(ir, dst, OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_RETENTION);
            if (isValidTopLogprobs(ir.get(OpenAiResponsesBodyPolicy.FIELD_TOP_LOGPROBS))) {
                dst.set(OpenAiResponsesBodyPolicy.FIELD_TOP_LOGPROBS,
                        ir.get(OpenAiResponsesBodyPolicy.FIELD_TOP_LOGPROBS));
                dst.put(FIELD_LOGPROBS, true);
            }
            if (ir.has(OpenAiResponsesBodyPolicy.FIELD_INCLUDE)
                    && containsTextValue(ir.get(OpenAiResponsesBodyPolicy.FIELD_INCLUDE),
                    OpenAiResponsesBodyPolicy.INCLUDE_MESSAGE_OUTPUT_TEXT_LOGPROBS)) {
                dst.put(FIELD_LOGPROBS, true);
            }

            // max_output_tokens → max_completion_tokens
            if (isPositiveInt(ir.get(OpenAiResponsesBodyPolicy.FIELD_MAX_OUTPUT_TOKENS))) {
                dst.put(OpenAiResponsesBodyPolicy.FIELD_MAX_COMPLETION_TOKENS,
                        ir.get(OpenAiResponsesBodyPolicy.FIELD_MAX_OUTPUT_TOKENS).asInt());
            }

            // 内部 IR stop 扩展 → Chat stop
            JsonNode stops = normalizeStopSequences(ir.get(GatewayProtocolIrPolicy.FIELD_STOP_SEQUENCES));
            if (stops != null) {
                if (stops.size() == 1) {
                    dst.put(FIELD_STOP, stops.get(0).asText());
                } else {
                    dst.set(FIELD_STOP, stops);
                }
            }

            // stream
            if (ir.has(FIELD_STREAM) && ir.get(FIELD_STREAM).isBoolean()) {
                dst.put(FIELD_STREAM, ir.get(FIELD_STREAM).asBoolean());
            }

            // reasoning.effort → reasoning_effort
            if (ir.has(OpenAiResponsesBodyPolicy.FIELD_REASONING)
                    && ir.get(OpenAiResponsesBodyPolicy.FIELD_REASONING).has(OpenAiResponsesBodyPolicy.FIELD_EFFORT)) {
                String effort = normalizeChatReasoningEffort(ir.get(OpenAiResponsesBodyPolicy.FIELD_REASONING)
                        .get(OpenAiResponsesBodyPolicy.FIELD_EFFORT));
                if (effort != null) {
                    dst.put(FIELD_REASONING_EFFORT, effort);
                }
            }

            // text.format → response_format
            if (ir.has(OpenAiResponsesBodyPolicy.FIELD_TEXT)
                    && ir.get(OpenAiResponsesBodyPolicy.FIELD_TEXT).has(OpenAiResponsesBodyPolicy.FIELD_FORMAT)) {
                JsonNode responseFormat = convertResponsesTextFormatToChat(ir.get(OpenAiResponsesBodyPolicy.FIELD_TEXT)
                        .get(OpenAiResponsesBodyPolicy.FIELD_FORMAT));
                if (responseFormat != null) {
                    dst.set(FIELD_RESPONSE_FORMAT, responseFormat);
                }
            }
            if (ir.has(OpenAiResponsesBodyPolicy.FIELD_TEXT)
                    && ir.get(OpenAiResponsesBodyPolicy.FIELD_TEXT).has(FIELD_VERBOSITY)) {
                String verbosity = normalizeVerbosity(ir.get(OpenAiResponsesBodyPolicy.FIELD_TEXT).get(FIELD_VERBOSITY));
                if (verbosity != null) {
                    dst.put(FIELD_VERBOSITY, verbosity);
                }
            }

            // --- input[] → messages[] ---
            ArrayNode messages = JSON.createArrayNode();

            // instructions → system message（放在最前面）
            if (ir.has(OpenAiResponsesBodyPolicy.FIELD_INSTRUCTIONS)
                    && !isBlankText(ir.get(OpenAiResponsesBodyPolicy.FIELD_INSTRUCTIONS))) {
                ObjectNode sysMsg = JSON.createObjectNode();
                sysMsg.put(FIELD_ROLE, ROLE_SYSTEM);
                sysMsg.put(FIELD_CONTENT, ir.get(OpenAiResponsesBodyPolicy.FIELD_INSTRUCTIONS).asText());
                messages.add(sysMsg);
            }

            if (ir.has(OpenAiResponsesBodyPolicy.FIELD_INPUT)) {
                JsonNode inputNode = ir.get(OpenAiResponsesBodyPolicy.FIELD_INPUT);
                if (inputNode.isArray()) {
                    ObjectNode pendingToolCallMsg = null;
                    ArrayNode pendingToolCalls = null;
                    for (JsonNode item : inputNode) {
                        String itemType = textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), null);
                        String role = textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_ROLE), null);

                        // function_call/custom_tool_call → assistant message with tool_calls
                        if (OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL.equals(itemType)
                                || OpenAiResponsesJsonPolicy.TYPE_CUSTOM_TOOL_CALL.equals(itemType)) {
                            if (isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID))
                                    || isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) {
                                log.debug("Responses→Chat: function/custom tool call missing call_id or name, ignored");
                                continue;
                            }
                            if (pendingToolCallMsg == null) {
                                pendingToolCallMsg = JSON.createObjectNode();
                                pendingToolCallMsg.put(FIELD_ROLE, ROLE_ASSISTANT);
                                pendingToolCallMsg.put(FIELD_CONTENT, "");
                                pendingToolCalls = JSON.createArrayNode();
                                pendingToolCallMsg.set(FIELD_TOOL_CALLS, pendingToolCalls);
                            }
                            ObjectNode tc = JSON.createObjectNode();
                            tc.put(FIELD_ID, item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID).asText());
                            if (OpenAiResponsesJsonPolicy.TYPE_CUSTOM_TOOL_CALL.equals(itemType)) {
                                tc.put(FIELD_TYPE, TYPE_CUSTOM);
                                ObjectNode custom = JSON.createObjectNode();
                                custom.put(FIELD_NAME, item.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText());
                                custom.put(FIELD_INPUT, textOrDefault(item.get(FIELD_INPUT), ""));
                                tc.set(FIELD_CUSTOM, custom);
                            } else {
                                tc.put(FIELD_TYPE, TYPE_FUNCTION);
                                ObjectNode func = JSON.createObjectNode();
                                func.put(FIELD_NAME, item.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText());
                                String args = textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS),
                                        DEFAULT_FUNCTION_ARGUMENTS);
                                func.put(FIELD_ARGUMENTS, args.isEmpty() ? DEFAULT_FUNCTION_ARGUMENTS : args);
                                tc.set(FIELD_FUNCTION, func);
                            }
                            pendingToolCalls.add(tc);
                            continue;
                        }

                        if (pendingToolCallMsg != null) {
                            messages.add(pendingToolCallMsg);
                            pendingToolCallMsg = null;
                            pendingToolCalls = null;
                        }

                        // function_call_output → tool message
                        if (OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL_OUTPUT.equals(itemType)) {
                            if (isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID))) {
                                log.debug("Responses→Chat: function_call_output missing call_id, ignored");
                                continue;
                            }
                            ObjectNode msg = JSON.createObjectNode();
                            msg.put(FIELD_ROLE, ROLE_TOOL);
                            msg.put(FIELD_TOOL_CALL_ID, item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID).asText());
                            msg.put(FIELD_CONTENT, textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT), ""));
                            messages.add(msg);
                            continue;
                        }

                        // 普通 message item
                        if (role != null) {
                            ObjectNode msg = JSON.createObjectNode();
                            msg.put(FIELD_ROLE, role);

                            JsonNode content = item.get(OpenAiResponsesJsonPolicy.FIELD_CONTENT);
                            if (content != null && content.isArray()) {
                                // 数组 content → Chat content
                                StringBuilder textContent = new StringBuilder();
                                ArrayNode multiParts = JSON.createArrayNode();
                                boolean hasImage = false;
                                boolean hasConvertiblePart = false;

                                for (JsonNode part : content) {
                                    String partType = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), "");
                                    switch (partType) {
                                        case OpenAiResponsesJsonPolicy.TYPE_INPUT_TEXT,
                                                OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT,
                                                TYPE_TEXT -> {
                                            String t = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_TEXT), "");
                                            if (t.isEmpty()) {
                                                break;
                                            }
                                            hasConvertiblePart = true;
                                            if (hasImage) {
                                                ObjectNode p = JSON.createObjectNode();
                                                p.put(FIELD_TYPE, TYPE_TEXT);
                                                p.put(FIELD_TEXT, t);
                                                multiParts.add(p);
                                            } else {
                                                if (textContent.length() > 0) textContent.append("\n");
                                                textContent.append(t);
                                            }
                                        }
                                        case OpenAiResponsesJsonPolicy.TYPE_REFUSAL -> {
                                            String t = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_REFUSAL), "");
                                            if (t.isEmpty()) {
                                                break;
                                            }
                                            hasConvertiblePart = true;
                                            if (hasImage) {
                                                ObjectNode p = JSON.createObjectNode();
                                                p.put(FIELD_TYPE, TYPE_TEXT);
                                                p.put(FIELD_TEXT, t);
                                                multiParts.add(p);
                                            } else {
                                                if (textContent.length() > 0) textContent.append("\n");
                                                textContent.append(t);
                                            }
                                        }
                                        case OpenAiResponsesJsonPolicy.TYPE_INPUT_IMAGE -> {
                                            if (isBlankText(part.get(OpenAiResponsesBodyPolicy.FIELD_IMAGE_URL))) {
                                                break;
                                            }
                                            hasImage = true;
                                            // 将已有文本转为 parts
                                            if (textContent.length() > 0) {
                                                ObjectNode p = JSON.createObjectNode();
                                                p.put(FIELD_TYPE, TYPE_TEXT);
                                                p.put(FIELD_TEXT, textContent.toString());
                                                multiParts.add(p);
                                                textContent.setLength(0);
                                            }
                                            ObjectNode p = JSON.createObjectNode();
                                            p.put(FIELD_TYPE, TYPE_IMAGE_URL);
                                            ObjectNode imageUrl = JSON.createObjectNode();
                                            imageUrl.put(FIELD_URL,
                                                    part.get(OpenAiResponsesBodyPolicy.FIELD_IMAGE_URL).asText());
                                            String detail = normalizeImageDetail(part.get(FIELD_DETAIL));
                                            if (detail != null) {
                                                imageUrl.put(FIELD_DETAIL, detail);
                                            }
                                            p.set(FIELD_IMAGE_URL, imageUrl);
                                            multiParts.add(p);
                                            hasConvertiblePart = true;
                                        }
                                        case OpenAiResponsesJsonPolicy.TYPE_INPUT_AUDIO -> {
                                            if (!part.has(FIELD_INPUT_AUDIO) || !part.get(FIELD_INPUT_AUDIO).isObject()
                                                    || !hasChatAudioPayload(part.get(FIELD_INPUT_AUDIO))) {
                                                break;
                                            }
                                            hasImage = true;
                                            if (textContent.length() > 0) {
                                                ObjectNode p = JSON.createObjectNode();
                                                p.put(FIELD_TYPE, TYPE_TEXT);
                                                p.put(FIELD_TEXT, textContent.toString());
                                                multiParts.add(p);
                                                textContent.setLength(0);
                                            }
                                            ObjectNode p = JSON.createObjectNode();
                                            p.put(FIELD_TYPE, TYPE_INPUT_AUDIO);
                                            p.set(FIELD_INPUT_AUDIO, part.get(FIELD_INPUT_AUDIO));
                                            multiParts.add(p);
                                            hasConvertiblePart = true;
                                        }
                                        case OpenAiResponsesJsonPolicy.TYPE_INPUT_FILE -> {
                                            if (!hasResponsesFilePayload(part)) {
                                                break;
                                            }
                                            hasImage = true;
                                            if (textContent.length() > 0) {
                                                ObjectNode p = JSON.createObjectNode();
                                                p.put(FIELD_TYPE, TYPE_TEXT);
                                                p.put(FIELD_TEXT, textContent.toString());
                                                multiParts.add(p);
                                                textContent.setLength(0);
                                            }
                                            ObjectNode p = JSON.createObjectNode();
                                            p.put(FIELD_TYPE, TYPE_FILE);
                                            ObjectNode file = JSON.createObjectNode();
                                            copyTextIfExists(part, file, OpenAiResponsesJsonPolicy.FIELD_FILE_DATA);
                                            copyTextIfExists(part, file, OpenAiResponsesJsonPolicy.FIELD_FILE_ID);
                                            copyTextIfExists(part, file, OpenAiResponsesJsonPolicy.FIELD_FILENAME);
                                            p.set(FIELD_FILE, file);
                                            multiParts.add(p);
                                            hasConvertiblePart = true;
                                        }
                                    }
                                }
                                if (!hasConvertiblePart) {
                                    continue;
                                }
                                if (hasImage) {
                                    msg.set(FIELD_CONTENT, multiParts);
                                } else {
                                    msg.put(FIELD_CONTENT, textContent.toString());
                                }
                            } else if (content != null && content.isTextual()) {
                                msg.put(FIELD_CONTENT, content.asText());
                            } else {
                                continue;
                            }
                            messages.add(msg);
                        }
                    }
                    if (pendingToolCallMsg != null) {
                        messages.add(pendingToolCallMsg);
                    }
                } else if (inputNode.isTextual()) {
                    ObjectNode msg = JSON.createObjectNode();
                    msg.put(FIELD_ROLE, ROLE_USER);
                    msg.put(FIELD_CONTENT, inputNode.asText());
                    messages.add(msg);
                }
            }

            dst.set(FIELD_MESSAGES, messages);

            // --- tools ---
            if (ir.has(OpenAiResponsesBodyPolicy.FIELD_TOOLS) && ir.get(OpenAiResponsesBodyPolicy.FIELD_TOOLS).isArray()) {
                ArrayNode chatTools = JSON.createArrayNode();
                for (JsonNode tool : ir.get(OpenAiResponsesBodyPolicy.FIELD_TOOLS)) {
                    String toolType = tool.has(OpenAiResponsesJsonPolicy.FIELD_TYPE)
                            ? tool.get(OpenAiResponsesJsonPolicy.FIELD_TYPE).asText()
                            : "";
                    if (OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL.equals(toolType) || TYPE_FUNCTION.equals(toolType)) {
                        if (isBlankText(tool.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) continue;
                        ObjectNode ct = JSON.createObjectNode();
                        ct.put(FIELD_TYPE, TYPE_FUNCTION);
                        ObjectNode func = JSON.createObjectNode();
                        func.put(FIELD_NAME, tool.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText());
                        copyTextIfExists(tool, func, FIELD_DESCRIPTION);
                        if (tool.has(FIELD_PARAMETERS)) {
                            func.set(FIELD_PARAMETERS, normalizeToolParameters(tool.get(FIELD_PARAMETERS)));
                        }
                        if (tool.has(FIELD_STRICT) && tool.get(FIELD_STRICT).isBoolean()) {
                            func.set(FIELD_STRICT, tool.get(FIELD_STRICT));
                        }
                        ct.set(FIELD_FUNCTION, func);
                        chatTools.add(ct);
                    } else if (TYPE_CUSTOM.equals(toolType)) {
                        if (isBlankText(tool.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) continue;
                        ObjectNode ct = JSON.createObjectNode();
                        ct.put(FIELD_TYPE, TYPE_CUSTOM);
                        ObjectNode custom = JSON.createObjectNode();
                        custom.put(FIELD_NAME, tool.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText());
                        copyTextIfExists(tool, custom, FIELD_DESCRIPTION);
                        JsonNode format = convertResponsesCustomToolFormatToChat(tool.get(FIELD_FORMAT));
                        if (format != null) {
                            custom.set(FIELD_FORMAT, format);
                        }
                        ct.set(FIELD_CUSTOM, custom);
                        chatTools.add(ct);
                    } else if (GatewayWebSearchToolPolicy.isAnthropicServerWebSearchToolType(toolType)) {
                        ObjectNode webSearchOptions = convertResponsesWebSearchToolToChatOptions(tool);
                        if (webSearchOptions != null) {
                            dst.set(FIELD_WEB_SEARCH_OPTIONS, webSearchOptions);
                        }
                    }
                    // 其他非 function 工具丢弃（Chat Completions 不支持）
                }
                if (chatTools.size() > 0) {
                    dst.set(FIELD_TOOLS, chatTools);
                }
            }

            // --- tool_choice ---
            if (ir.has(OpenAiResponsesBodyPolicy.FIELD_TOOL_CHOICE)) {
                JsonNode toolChoice = convertResponsesToolChoiceToChat(ir.get(OpenAiResponsesBodyPolicy.FIELD_TOOL_CHOICE));
                if (toolChoice != null) {
                    dst.set(FIELD_TOOL_CHOICE, toolChoice);
                }
            }

            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Responses→ChatCompletions requestFromIR error: {}", e.getMessage());
            return "{}";
        }
    }

    // ========================
    // 非流式响应反向转换：Responses IR → Chat Completions
    // ========================

    /**
     * 将 Responses 非流式响应（IR）转换为 Chat Completions 响应。
     */
    public String responseFromIR(JsonNode ir) {
        try {
            ObjectNode dst = JSON.createObjectNode();

            dst.put(FIELD_ID, textOrDefault(ir.get(OpenAiResponsesJsonPolicy.FIELD_ID),
                    ID_PREFIX_CHAT_COMPLETION + UUID.randomUUID().toString().replace("-", "").substring(0, ID_RANDOM_LENGTH)));
            dst.put(FIELD_OBJECT, OBJECT_CHAT_COMPLETION);
            dst.put(FIELD_CREATED, isNonNegativeLong(ir.get(OpenAiResponsesJsonPolicy.FIELD_CREATED_AT))
                    ? ir.get(OpenAiResponsesJsonPolicy.FIELD_CREATED_AT).asLong()
                    : System.currentTimeMillis() / 1000);
            dst.put(FIELD_MODEL, isBlankText(ir.get(OpenAiResponsesJsonPolicy.FIELD_MODEL))
                    ? DEFAULT_MODEL
                    : ir.get(OpenAiResponsesJsonPolicy.FIELD_MODEL).asText());

            // output[] → message content + tool_calls + reasoning_content
            StringBuilder contentText = new StringBuilder();
            StringBuilder reasoningText = new StringBuilder();
            ArrayNode toolCalls = JSON.createArrayNode();
            boolean hasToolCalls = false;

            if (ir.has(OpenAiResponsesJsonPolicy.FIELD_OUTPUT) && ir.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT).isArray()) {
                for (JsonNode item : ir.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT)) {
                    String itemType = textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), "");

                    switch (itemType) {
                        case OpenAiResponsesJsonPolicy.TYPE_REASONING -> {
                            String text = extractReasoningText(item);
                            if (!text.isEmpty()) {
                                reasoningText.append(text);
                            }
                        }
                        case OpenAiResponsesJsonPolicy.TYPE_MESSAGE -> {
                            if (item.has(OpenAiResponsesJsonPolicy.FIELD_CONTENT)
                                    && item.get(OpenAiResponsesJsonPolicy.FIELD_CONTENT).isArray()) {
                                for (JsonNode part : item.get(OpenAiResponsesJsonPolicy.FIELD_CONTENT)) {
                                    String partType = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), "");
                                    String text = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_TEXT), "");
                                    String refusal = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_REFUSAL), "");
                                    if (OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT.equals(partType) && !text.isEmpty()) {
                                        contentText.append(text);
                                    } else if (OpenAiResponsesJsonPolicy.TYPE_REFUSAL.equals(partType) && !refusal.isEmpty()) {
                                        contentText.append(refusal);
                                    }
                                }
                            }
                        }
                        case OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL -> {
                            if (isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID))
                                    || isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) {
                                log.debug("Responses→Chat: output function_call missing call_id or name, ignored");
                            } else {
                                hasToolCalls = true;
                                ObjectNode tc = JSON.createObjectNode();
                                tc.put(FIELD_ID, item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID).asText());
                                tc.put(FIELD_TYPE, TYPE_FUNCTION);
                                ObjectNode func = JSON.createObjectNode();
                                func.put(FIELD_NAME, item.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText());
                                String args = textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS),
                                        DEFAULT_FUNCTION_ARGUMENTS);
                                func.put(FIELD_ARGUMENTS, args.isEmpty() ? DEFAULT_FUNCTION_ARGUMENTS : args);
                                tc.set(FIELD_FUNCTION, func);
                                toolCalls.add(tc);
                            }
                        }
                        case OpenAiResponsesJsonPolicy.TYPE_CUSTOM_TOOL_CALL -> {
                            if (isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID))
                                    || isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) {
                                log.debug("Responses→Chat: output custom_tool_call missing call_id or name, ignored");
                            } else {
                                hasToolCalls = true;
                                ObjectNode tc = JSON.createObjectNode();
                                tc.put(FIELD_ID, item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID).asText());
                                tc.put(FIELD_TYPE, TYPE_CUSTOM);
                                ObjectNode custom = JSON.createObjectNode();
                                custom.put(FIELD_NAME, item.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText());
                                custom.put(FIELD_INPUT, textOrDefault(item.get(FIELD_INPUT), ""));
                                tc.set(FIELD_CUSTOM, custom);
                                toolCalls.add(tc);
                            }
                        }
                        case OpenAiResponsesJsonPolicy.TYPE_WEB_SEARCH_CALL -> { /* 丢弃 */ }
                        default ->
                            log.debug("Responses→Chat: unknown output type '{}'", itemType);
                    }
                }
            }

            // 构建 choices[0].message
            ArrayNode choices = JSON.createArrayNode();
            ObjectNode choice = JSON.createObjectNode();
            choice.put(FIELD_INDEX, 0);
            ObjectNode message = JSON.createObjectNode();
            message.put(FIELD_ROLE, ROLE_ASSISTANT);
            if (contentText.length() > 0) {
                message.put(FIELD_CONTENT, contentText.toString());
            }
            if (hasToolCalls) {
                message.set(FIELD_TOOL_CALLS, toolCalls);
            }
            if (reasoningText.length() > 0) {
                message.put(FIELD_REASONING_CONTENT, reasoningText.toString());
            }
            choice.set(FIELD_MESSAGE, message);

            // finish_reason
            String status = textOrDefault(ir.get(OpenAiResponsesJsonPolicy.FIELD_STATUS),
                    OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
            String incompleteReason = textOrDefault(ir.path(OpenAiResponsesJsonPolicy.FIELD_INCOMPLETE_DETAILS)
                    .get(OpenAiResponsesJsonPolicy.FIELD_REASON), "");
            String finishReason = mapResponsesStatusToChatFinishReason(status, incompleteReason, hasToolCalls);
            choice.put(FIELD_FINISH_REASON, finishReason);
            choices.add(choice);
            dst.set(FIELD_CHOICES, choices);

            // usage
            if (ir.has(OpenAiResponsesJsonPolicy.FIELD_USAGE)) {
                JsonNode usage = ir.get(OpenAiResponsesJsonPolicy.FIELD_USAGE);
                ObjectNode chatUsage = JSON.createObjectNode();
                int inputTokens = nonNegativeIntOrZero(usage.get(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS));
                int outputTokens = nonNegativeIntOrZero(usage.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_TOKENS));
                chatUsage.put(FIELD_PROMPT_TOKENS, inputTokens);
                chatUsage.put(FIELD_COMPLETION_TOKENS, outputTokens);
                chatUsage.put(FIELD_TOTAL_TOKENS, inputTokens + outputTokens);
                if (isPositiveInt(usage.path(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS)
                        .get(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS))) {
                    ObjectNode details = JSON.createObjectNode();
                    details.put(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS,
                            usage.get(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS)
                                    .get(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS).asInt());
                    chatUsage.set(FIELD_PROMPT_TOKENS_DETAILS, details);
                }
                dst.set(FIELD_USAGE, chatUsage);
            }

            return JSON.writeValueAsString(dst);
        } catch (Exception e) {
            log.warn("Responses→ChatCompletions responseFromIR error: {}", e.getMessage());
            return "{}";
        }
    }

    // ========================
    // 流式 SSE 翻译：Responses SSE（IR）→ Chat Completions SSE
    // ========================

    /**
     * 创建 Responses SSE → Chat Completions SSE 流式翻译器。
     */
    public StreamTranslator createStreamFromIR(String model) {
        return new IRToChatStreamTranslator(model);
    }

    // ========================
    // 流式翻译器内部类
    // ========================

    /**
     * Responses SSE 事件 → Chat Completions SSE chunk。
     */
    static class IRToChatStreamTranslator implements StreamTranslator {

        private boolean done = false;
        private boolean sentRole = false;
        private boolean sawToolCall = false;
        private String completionId;
        private String model;
        private long created;

        private int inputTokens = 0;
        private int outputTokens = 0;
        private int cachedTokens = 0;
        private boolean usageSeen = false;

        private final Map<Integer, Integer> outputIndexToToolIndex = new HashMap<>();
        private final Set<Integer> textDeltasSeen = new HashSet<>();
        private final Set<Integer> refusalDeltasSeen = new HashSet<>();
        private final Set<Integer> toolArgumentDeltasSeen = new HashSet<>();
        private final Set<Integer> reasoningDeltasSeen = new HashSet<>();
        private int nextToolCallIndex = 0;

        IRToChatStreamTranslator(String model) {
            this.model = model != null ? model : DEFAULT_MODEL;
            this.completionId = ID_PREFIX_CHAT_COMPLETION
                    + UUID.randomUUID().toString().replace("-", "").substring(0, ID_RANDOM_LENGTH);
            this.created = System.currentTimeMillis() / 1000;
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
                        if (root.has(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)) {
                            JsonNode resp = root.get(OpenAiResponsesJsonPolicy.FIELD_RESPONSE);
                            if (!isBlankText(resp.get(OpenAiResponsesJsonPolicy.FIELD_ID))) {
                                completionId = resp.get(OpenAiResponsesJsonPolicy.FIELD_ID).asText();
                            }
                            if ((model == null || model.isBlank() || DEFAULT_MODEL.equals(model))
                                    && !isBlankText(resp.get(OpenAiResponsesJsonPolicy.FIELD_MODEL))) {
                                model = resp.get(OpenAiResponsesJsonPolicy.FIELD_MODEL).asText();
                            }
                        }
                        if (!sentRole) {
                            appendDataLine(output, formatDeltaChunk(ROLE_ASSISTANT, null, null, null));
                            sentRole = true;
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_OUTPUT_TEXT_DELTA -> {
                        String text = textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_DELTA), "");
                        if (!text.isEmpty()) {
                            textDeltasSeen.add(contentKey(root));
                            appendDataLine(output, formatDeltaChunk(null, text, null, null));
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_OUTPUT_TEXT_DONE -> {
                        String text = textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_TEXT), "");
                        int key = contentKey(root);
                        if (!text.isEmpty() && !textDeltasSeen.contains(key)) {
                            textDeltasSeen.add(key);
                            appendDataLine(output, formatDeltaChunk(null, text, null, null));
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_CONTENT_PART_DONE -> {
                        JsonNode part = root.path(OpenAiResponsesJsonPolicy.FIELD_PART);
                        String partType = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), "");
                        int key = contentKey(root);
                        if (OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT.equals(partType) && !textDeltasSeen.contains(key)) {
                            String text = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_TEXT), "");
                            if (!text.isEmpty()) {
                                textDeltasSeen.add(key);
                                appendDataLine(output, formatDeltaChunk(null, text, null, null));
                            }
                        } else if (OpenAiResponsesJsonPolicy.TYPE_REFUSAL.equals(partType) && !refusalDeltasSeen.contains(key)) {
                            String refusal = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_REFUSAL), "");
                            if (!refusal.isEmpty()) {
                                refusalDeltasSeen.add(key);
                                appendDataLine(output, formatDeltaChunk(null, refusal, null, null));
                            }
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_REFUSAL_DELTA -> {
                        String delta = textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_DELTA), "");
                        if (!delta.isEmpty()) {
                            refusalDeltasSeen.add(contentKey(root));
                            appendDataLine(output, formatDeltaChunk(null, delta, null, null));
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_REFUSAL_DONE -> {
                        String refusal = textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_REFUSAL), "");
                        int key = contentKey(root);
                        if (!refusal.isEmpty() && !refusalDeltasSeen.contains(key)) {
                            refusalDeltasSeen.add(key);
                            appendDataLine(output, formatDeltaChunk(null, refusal, null, null));
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_OUTPUT_ITEM_ADDED -> {
                        if (root.has(OpenAiResponsesJsonPolicy.FIELD_ITEM)
                                && (OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL.equals(textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_ITEM).get(OpenAiResponsesJsonPolicy.FIELD_TYPE), ""))
                                || OpenAiResponsesJsonPolicy.TYPE_CUSTOM_TOOL_CALL.equals(textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_ITEM).get(OpenAiResponsesJsonPolicy.FIELD_TYPE), "")))) {
                            JsonNode item = root.get(OpenAiResponsesJsonPolicy.FIELD_ITEM);
                            if (isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID))
                                    || isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) {
                                log.debug("Responses→Chat stream: tool call missing call_id or name, ignored");
                                return output;
                            }
                            sawToolCall = true;
                            String callId = item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID).asText();
                            String toolName = item.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText();
                            int outputIdx = nonNegativeIntOrZero(root.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX));

                            int chatIdx = nextToolCallIndex++;
                            outputIndexToToolIndex.put(outputIdx, chatIdx);

                            if (OpenAiResponsesJsonPolicy.TYPE_CUSTOM_TOOL_CALL.equals(
                                    textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), ""))) {
                                appendDataLine(output, formatCustomToolCallChunk(chatIdx, callId, toolName, null));
                            } else {
                                appendDataLine(output, formatToolCallChunk(chatIdx, callId, toolName, null));
                            }
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_FUNCTION_CALL_ARGUMENTS_DELTA -> {
                        String delta = textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_DELTA), "");
                        if (!delta.isEmpty()) {
                            int outputIdx = nonNegativeIntOrZero(root.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX));
                            Integer chatIdx = outputIndexToToolIndex.get(outputIdx);
                            if (chatIdx != null) {
                                toolArgumentDeltasSeen.add(outputIdx);
                                appendDataLine(output, formatToolCallChunk(chatIdx, null, null, delta));
                            }
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_OUTPUT_ITEM_DONE -> {
                        if (root.has(OpenAiResponsesJsonPolicy.FIELD_ITEM)) {
                            int outputIdx = nonNegativeIntOrZero(root.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX));
                            emitFinalOutputItem(output, outputIdx, root.get(OpenAiResponsesJsonPolicy.FIELD_ITEM));
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_REASONING_SUMMARY_TEXT_DELTA,
                            OpenAiResponsesSsePolicy.EVENT_REASONING_TEXT_DELTA -> {
                        String delta = textOrDefault(root.get(OpenAiResponsesJsonPolicy.FIELD_DELTA), "");
                        if (!delta.isEmpty()) {
                            int outputIdx = nonNegativeIntOrZero(root.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX));
                            reasoningDeltasSeen.add(outputIdx);
                            appendDataLine(output, formatDeltaChunk(null, null, delta, null));
                        }
                    }
                    case OpenAiResponsesSsePolicy.EVENT_RESPONSE_COMPLETED,
                            OpenAiResponsesSsePolicy.EVENT_RESPONSE_DONE,
                            OpenAiResponsesSsePolicy.EVENT_RESPONSE_INCOMPLETE,
                            OpenAiResponsesSsePolicy.EVENT_RESPONSE_FAILED -> {
                        if (root.has(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                                && root.get(OpenAiResponsesJsonPolicy.FIELD_RESPONSE).has(OpenAiResponsesJsonPolicy.FIELD_OUTPUT)
                                && root.get(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                                .get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT).isArray()) {
                            JsonNode responseOutput = root.get(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                                    .get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT);
                            for (int i = 0; i < responseOutput.size(); i++) {
                                emitFinalOutputItem(output, i, responseOutput.get(i));
                            }
                        }

                        // usage
                        if (root.has(OpenAiResponsesJsonPolicy.FIELD_USAGE)) {
                            extractUsage(root.get(OpenAiResponsesJsonPolicy.FIELD_USAGE));
                        } else if (root.has(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                                && root.get(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                                .has(OpenAiResponsesJsonPolicy.FIELD_USAGE)) {
                            extractUsage(root.get(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                                    .get(OpenAiResponsesJsonPolicy.FIELD_USAGE));
                        }

                        // finish_reason
                        String finishReason;
                        if (root.has(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)) {
                            JsonNode resp = root.get(OpenAiResponsesJsonPolicy.FIELD_RESPONSE);
                            String respStatus = textOrDefault(resp.get(OpenAiResponsesJsonPolicy.FIELD_STATUS),
                                    OpenAiResponsesJsonPolicy.STATUS_COMPLETED);
                            if (OpenAiResponsesJsonPolicy.STATUS_INCOMPLETE.equals(respStatus)) {
                                String incompleteReason = textOrDefault(
                                        resp.path(OpenAiResponsesJsonPolicy.FIELD_INCOMPLETE_DETAILS)
                                                .get(OpenAiResponsesJsonPolicy.FIELD_REASON), "");
                                finishReason = mapResponsesIncompleteReasonToChatFinishReason(incompleteReason);
                            } else if (OpenAiResponsesJsonPolicy.STATUS_COMPLETED.equals(respStatus) && sawToolCall) {
                                finishReason = FINISH_REASON_TOOL_CALLS;
                            } else {
                                finishReason = FINISH_REASON_STOP;
                            }
                        } else {
                            finishReason = sawToolCall ? FINISH_REASON_TOOL_CALLS : FINISH_REASON_STOP;
                        }

                        appendDataLine(output, formatFinishChunk(finishReason));
                        if (usageSeen) {
                            appendDataLine(output, formatUsageChunk());
                        }
                        appendDataLine(output, OpenAiResponsesSsePolicy.DONE_SENTINEL);
                        done = true;
                    }
                }
            } catch (Exception e) {
                log.debug("IR→Chat SSE error: {}", e.getMessage());
            }
            return output;
        }

        private void emitFinalOutputItem(List<String> output, int outputIdx, JsonNode item) {
            String itemType = textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), "");
            if (OpenAiResponsesJsonPolicy.TYPE_MESSAGE.equals(itemType)
                    && item.has(OpenAiResponsesJsonPolicy.FIELD_CONTENT)
                    && item.get(OpenAiResponsesJsonPolicy.FIELD_CONTENT).isArray()) {
                for (int contentIdx = 0; contentIdx < item.get(OpenAiResponsesJsonPolicy.FIELD_CONTENT).size(); contentIdx++) {
                    JsonNode part = item.get(OpenAiResponsesJsonPolicy.FIELD_CONTENT).get(contentIdx);
                    String partType = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), "");
                    int key = outputIdx * 10_000 + contentIdx;
                    if (OpenAiResponsesJsonPolicy.TYPE_OUTPUT_TEXT.equals(partType) && !textDeltasSeen.contains(key)) {
                        String text = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_TEXT), "");
                        if (!text.isEmpty()) {
                            textDeltasSeen.add(key);
                            appendDataLine(output, formatDeltaChunk(null, text, null, null));
                        }
                    } else if (OpenAiResponsesJsonPolicy.TYPE_REFUSAL.equals(partType) && !refusalDeltasSeen.contains(key)) {
                        String refusal = textOrDefault(part.get(OpenAiResponsesJsonPolicy.FIELD_REFUSAL), "");
                        if (!refusal.isEmpty()) {
                            refusalDeltasSeen.add(key);
                            appendDataLine(output, formatDeltaChunk(null, refusal, null, null));
                        }
                    }
                }
            } else if (OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL.equals(itemType)) {
                if (isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID))
                        || isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) {
                    log.debug("Responses→Chat stream: final function_call missing call_id or name, ignored");
                    return;
                }
                sawToolCall = true;
                Integer chatIdx = outputIndexToToolIndex.get(outputIdx);
                if (chatIdx == null) {
                    chatIdx = nextToolCallIndex++;
                    outputIndexToToolIndex.put(outputIdx, chatIdx);
                    appendDataLine(output, formatToolCallChunk(chatIdx,
                            textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID), ""),
                            textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_NAME), ""),
                            null));
                }
                String arguments = textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_ARGUMENTS), "");
                if (!arguments.isEmpty() && !toolArgumentDeltasSeen.contains(outputIdx)) {
                    toolArgumentDeltasSeen.add(outputIdx);
                    appendDataLine(output, formatToolCallChunk(chatIdx, null, null, arguments));
                }
            } else if (OpenAiResponsesJsonPolicy.TYPE_CUSTOM_TOOL_CALL.equals(itemType)) {
                if (isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID))
                        || isBlankText(item.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) {
                    log.debug("Responses→Chat stream: final custom_tool_call missing call_id or name, ignored");
                    return;
                }
                sawToolCall = true;
                Integer chatIdx = outputIndexToToolIndex.get(outputIdx);
                if (chatIdx == null) {
                    chatIdx = nextToolCallIndex++;
                    outputIndexToToolIndex.put(outputIdx, chatIdx);
                    appendDataLine(output, formatCustomToolCallChunk(chatIdx,
                            textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID), ""),
                            textOrDefault(item.get(OpenAiResponsesJsonPolicy.FIELD_NAME), ""),
                            null));
                }
                String input = textOrDefault(item.get(FIELD_INPUT), "");
                if (!input.isEmpty() && !toolArgumentDeltasSeen.contains(outputIdx)) {
                    toolArgumentDeltasSeen.add(outputIdx);
                    appendDataLine(output, formatCustomToolCallChunk(chatIdx, null, null, input));
                }
            } else if (OpenAiResponsesJsonPolicy.TYPE_REASONING.equals(itemType) && !reasoningDeltasSeen.contains(outputIdx)
                    && (item.has(OpenAiResponsesJsonPolicy.FIELD_CONTENT)
                    || item.has(OpenAiResponsesJsonPolicy.FIELD_SUMMARY))) {
                String reasoning = extractReasoningText(item);
                if (!reasoning.isEmpty()) {
                    reasoningDeltasSeen.add(outputIdx);
                    appendDataLine(output, formatDeltaChunk(null, null, reasoning, null));
                }
            }
        }

        private void appendDataLine(List<String> output, String payload) {
            output.add(OpenAiResponsesSsePolicy.DATA_LINE_PREFIX + payload);
            output.add(OpenAiResponsesSsePolicy.FRAME_SEPARATOR_LINE);
        }

        private String formatDeltaChunk(String role, String content, String reasoningContent, String finishReason) {
            ObjectNode chunk = baseChatChunk();
            ObjectNode choice = firstChoice(chunk);
            ObjectNode delta = JSON.createObjectNode();
            if (role != null) {
                delta.put(FIELD_ROLE, role);
            }
            if (content != null) {
                delta.put(FIELD_CONTENT, content);
            }
            if (reasoningContent != null) {
                delta.put(FIELD_REASONING_CONTENT, reasoningContent);
            }
            choice.set(FIELD_DELTA, delta);
            putNullableText(choice, FIELD_FINISH_REASON, finishReason);
            return chunk.toString();
        }

        private int contentKey(JsonNode root) {
            int outputIdx = nonNegativeIntOrZero(root.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_INDEX));
            int contentIdx = nonNegativeIntOrZero(root.get(OpenAiResponsesJsonPolicy.FIELD_CONTENT_INDEX));
            return outputIdx * 10_000 + contentIdx;
        }

        private String formatToolCallChunk(int index, String id, String name, String arguments) {
            ObjectNode chunk = baseChatChunk();
            ObjectNode choice = firstChoice(chunk);
            ObjectNode delta = JSON.createObjectNode();
            ArrayNode toolCalls = JSON.createArrayNode();
            ObjectNode toolCall = JSON.createObjectNode();
            toolCall.put(FIELD_INDEX, index);
            if (id != null) {
                toolCall.put(FIELD_ID, id);
                toolCall.put(FIELD_TYPE, TYPE_FUNCTION);
            }
            if (name != null || arguments != null) {
                ObjectNode function = JSON.createObjectNode();
                if (name != null) {
                    function.put(FIELD_NAME, name);
                }
                if (arguments != null) {
                    function.put(FIELD_ARGUMENTS, arguments);
                }
                toolCall.set(FIELD_FUNCTION, function);
            }
            toolCalls.add(toolCall);
            delta.set(FIELD_TOOL_CALLS, toolCalls);
            choice.set(FIELD_DELTA, delta);
            choice.putNull(FIELD_FINISH_REASON);
            return chunk.toString();
        }

        private String formatCustomToolCallChunk(int index, String id, String name, String input) {
            ObjectNode chunk = baseChatChunk();
            ObjectNode choice = firstChoice(chunk);
            ObjectNode delta = JSON.createObjectNode();
            ArrayNode toolCalls = JSON.createArrayNode();
            ObjectNode toolCall = JSON.createObjectNode();
            toolCall.put(FIELD_INDEX, index);
            if (id != null) {
                toolCall.put(FIELD_ID, id);
                toolCall.put(FIELD_TYPE, TYPE_CUSTOM);
            }
            if (name != null || input != null) {
                ObjectNode custom = JSON.createObjectNode();
                if (name != null) {
                    custom.put(FIELD_NAME, name);
                }
                if (input != null) {
                    custom.put(FIELD_INPUT, input);
                }
                toolCall.set(FIELD_CUSTOM, custom);
            }
            toolCalls.add(toolCall);
            delta.set(FIELD_TOOL_CALLS, toolCalls);
            choice.set(FIELD_DELTA, delta);
            choice.putNull(FIELD_FINISH_REASON);
            return chunk.toString();
        }

        private String formatFinishChunk(String finishReason) {
            ObjectNode chunk = baseChatChunk();
            ObjectNode choice = firstChoice(chunk);
            ObjectNode delta = JSON.createObjectNode();
            delta.put(FIELD_CONTENT, "");
            choice.set(FIELD_DELTA, delta);
            choice.put(FIELD_FINISH_REASON, finishReason);
            return chunk.toString();
        }

        private String formatUsageChunk() {
            ObjectNode chunk = baseChatChunk();
            chunk.set(FIELD_CHOICES, JSON.createArrayNode());
            ObjectNode usage = JSON.createObjectNode();
            usage.put(FIELD_PROMPT_TOKENS, inputTokens);
            usage.put(FIELD_COMPLETION_TOKENS, outputTokens);
            usage.put(FIELD_TOTAL_TOKENS, inputTokens + outputTokens);
            if (cachedTokens > 0) {
                ObjectNode promptDetails = JSON.createObjectNode();
                promptDetails.put(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS, cachedTokens);
                usage.set(FIELD_PROMPT_TOKENS_DETAILS, promptDetails);
            }
            chunk.set(FIELD_USAGE, usage);
            return chunk.toString();
        }

        private ObjectNode baseChatChunk() {
            ObjectNode chunk = JSON.createObjectNode();
            chunk.put(FIELD_ID, completionId);
            chunk.put(FIELD_OBJECT, OBJECT_CHAT_COMPLETION_CHUNK);
            chunk.put(FIELD_CREATED, created);
            chunk.put(FIELD_MODEL, model);
            ArrayNode choices = JSON.createArrayNode();
            ObjectNode choice = JSON.createObjectNode();
            choice.put(FIELD_INDEX, 0);
            choices.add(choice);
            chunk.set(FIELD_CHOICES, choices);
            return chunk;
        }

        private ObjectNode firstChoice(ObjectNode chunk) {
            return (ObjectNode) chunk.get(FIELD_CHOICES).get(0);
        }

        private void putNullableText(ObjectNode node, String field, String value) {
            if (value == null) {
                node.putNull(field);
            } else {
                node.put(field, value);
            }
        }

        private void extractUsage(JsonNode usage) {
            usageSeen = true;
            if (isNonNegativeInt(usage.get(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS))) {
                inputTokens = usage.get(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS).asInt();
            }
            if (isNonNegativeInt(usage.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_TOKENS))) {
                outputTokens = usage.get(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_TOKENS).asInt();
            }
            if (usage.has(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS)
                    && isNonNegativeInt(usage.get(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS)
                    .get(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS))) {
                cachedTokens = usage.get(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS)
                        .get(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS).asInt();
            }
        }

        @Override public boolean isDone() { return done; }
        @Override public int getInputTokens() { return inputTokens; }
        @Override public int getOutputTokens() { return outputTokens; }
    }

    // ========================
    // 辅助方法
    // ========================

    /**
     * Responses tool_choice → Chat tool_choice。
     * name 嵌套层级适配：
     * {"type":"function","name":"X"} → {"type":"function","function":{"name":"X"}}
     * {"type":"custom","name":"X"} → {"type":"custom","custom":{"name":"X"}}
     */
    private static JsonNode convertResponsesToolChoiceToChat(JsonNode toolChoice) {
        if (toolChoice.isTextual()) {
            return isSupportedToolChoiceMode(toolChoice.asText()) ? toolChoice : null;
        }
        if (toolChoice.isObject() && TYPE_FUNCTION.equals(toolChoice.has(OpenAiResponsesJsonPolicy.FIELD_TYPE)
                ? toolChoice.get(OpenAiResponsesJsonPolicy.FIELD_TYPE).asText() : "")) {
            if (isBlankText(toolChoice.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put(FIELD_TYPE, TYPE_FUNCTION);
            ObjectNode func = JSON.createObjectNode();
            func.put(FIELD_NAME, toolChoice.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText());
            obj.set(FIELD_FUNCTION, func);
            return obj;
        }
        if (toolChoice.isObject() && TYPE_CUSTOM.equals(toolChoice.has(OpenAiResponsesJsonPolicy.FIELD_TYPE)
                ? toolChoice.get(OpenAiResponsesJsonPolicy.FIELD_TYPE).asText() : "")) {
            if (isBlankText(toolChoice.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put(FIELD_TYPE, TYPE_CUSTOM);
            ObjectNode custom = JSON.createObjectNode();
            custom.put(FIELD_NAME, toolChoice.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText());
            obj.set(FIELD_CUSTOM, custom);
            return obj;
        }
        if (toolChoice.isObject() && TYPE_ALLOWED_TOOLS.equals(toolChoice.has(OpenAiResponsesJsonPolicy.FIELD_TYPE)
                ? toolChoice.get(OpenAiResponsesJsonPolicy.FIELD_TYPE).asText() : "")) {
            ObjectNode obj = JSON.createObjectNode();
            obj.put(FIELD_TYPE, TYPE_ALLOWED_TOOLS);
            ObjectNode allowed = JSON.createObjectNode();
            String mode = normalizeAllowedToolsMode(toolChoice.get(FIELD_MODE));
            if (mode != null) {
                allowed.put(FIELD_MODE, mode);
            }
            if (toolChoice.has(OpenAiResponsesBodyPolicy.FIELD_TOOLS)
                    && toolChoice.get(OpenAiResponsesBodyPolicy.FIELD_TOOLS).isArray()) {
                ArrayNode tools = JSON.createArrayNode();
                for (JsonNode tool : toolChoice.get(OpenAiResponsesBodyPolicy.FIELD_TOOLS)) {
                    JsonNode converted = convertResponsesAllowedToolToChat(tool);
                    if (converted != null) tools.add(converted);
                }
                if (tools.size() == 0) return null;
                allowed.set(FIELD_TOOLS, tools);
            }
            obj.set(FIELD_ALLOWED_TOOLS, allowed);
            return obj;
        }
        return null;
    }

    private static boolean isSupportedToolChoiceMode(String mode) {
        return OpenAiChatCompletionsBodyPolicy.isSupportedToolChoiceMode(mode);
    }

    private static JsonNode convertResponsesAllowedToolToChat(JsonNode tool) {
        if (tool == null || !tool.isObject()) return null;
        String type = tool.path(OpenAiResponsesJsonPolicy.FIELD_TYPE).asText("");
        if (TYPE_FUNCTION.equals(type)) {
            if (isBlankText(tool.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put(FIELD_TYPE, TYPE_FUNCTION);
            ObjectNode func = JSON.createObjectNode();
            func.put(FIELD_NAME, tool.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText());
            obj.set(FIELD_FUNCTION, func);
            return obj;
        }
        if (TYPE_CUSTOM.equals(type)) {
            if (isBlankText(tool.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) return null;
            ObjectNode obj = JSON.createObjectNode();
            obj.put(FIELD_TYPE, TYPE_CUSTOM);
            ObjectNode custom = JSON.createObjectNode();
            custom.put(FIELD_NAME, tool.get(OpenAiResponsesJsonPolicy.FIELD_NAME).asText());
            obj.set(FIELD_CUSTOM, custom);
            return obj;
        }
        return null;
    }

    private static JsonNode convertResponsesCustomToolFormatToChat(JsonNode format) {
        if (format == null || !format.isObject()
                || isBlankText(format.get(OpenAiResponsesJsonPolicy.FIELD_TYPE))) return null;
        String type = format.get(OpenAiResponsesJsonPolicy.FIELD_TYPE).asText();
        ObjectNode normalized = JSON.createObjectNode();
        if (TYPE_TEXT.equals(type)) {
            normalized.put(FIELD_TYPE, TYPE_TEXT);
            return normalized;
        }
        if (TYPE_GRAMMAR.equals(type)) {
            String syntax = normalizeCustomToolGrammarSyntax(format.get(FIELD_SYNTAX));
            if (syntax == null || isBlankText(format.get(FIELD_DEFINITION))) return null;
            ObjectNode grammar = JSON.createObjectNode();
            grammar.put(FIELD_SYNTAX, syntax);
            grammar.put(FIELD_DEFINITION, format.get(FIELD_DEFINITION).asText());
            normalized.put(FIELD_TYPE, TYPE_GRAMMAR);
            normalized.set(TYPE_GRAMMAR, grammar);
            return normalized;
        }
        return null;
    }

    private static String normalizeCustomToolGrammarSyntax(JsonNode syntax) {
        if (syntax == null || !syntax.isTextual()) return null;
        return OpenAiChatCompletionsBodyPolicy.normalizeCustomToolGrammarSyntax(syntax.asText());
    }

    private static JsonNode convertResponsesTextFormatToChat(JsonNode format) {
        String type = format.path(OpenAiResponsesJsonPolicy.FIELD_TYPE).asText("");
        if (TYPE_JSON_SCHEMA.equals(type)) {
            if (isBlankText(format.get(OpenAiResponsesJsonPolicy.FIELD_NAME))) return null;
            if (!format.has(FIELD_SCHEMA) || format.get(FIELD_SCHEMA).isNull()) return null;
            ObjectNode responseFormat = JSON.createObjectNode();
            responseFormat.put(FIELD_TYPE, TYPE_JSON_SCHEMA);
            ObjectNode jsonSchema = JSON.createObjectNode();
            jsonSchema.set(FIELD_NAME, format.get(OpenAiResponsesJsonPolicy.FIELD_NAME));
            copyTextIfExists(format, jsonSchema, FIELD_DESCRIPTION);
            jsonSchema.set(FIELD_SCHEMA, format.get(FIELD_SCHEMA));
            if (format.has(FIELD_STRICT) && format.get(FIELD_STRICT).isBoolean()) {
                jsonSchema.set(FIELD_STRICT, format.get(FIELD_STRICT));
            }
            responseFormat.set(FIELD_JSON_SCHEMA, jsonSchema);
            return responseFormat;
        }
        if (TYPE_JSON_OBJECT.equals(type)) {
            ObjectNode responseFormat = JSON.createObjectNode();
            responseFormat.put(FIELD_TYPE, TYPE_JSON_OBJECT);
            return responseFormat;
        }
        if (TYPE_TEXT.equals(type)) {
            ObjectNode responseFormat = JSON.createObjectNode();
            responseFormat.put(FIELD_TYPE, TYPE_TEXT);
            return responseFormat;
        }
        return null;
    }

    private static ObjectNode convertResponsesWebSearchToolToChatOptions(JsonNode tool) {
        if (tool == null || !tool.isObject()) return null;
        ObjectNode options = JSON.createObjectNode();
        String searchContextSize = normalizeSearchContextSize(tool.get(FIELD_SEARCH_CONTEXT_SIZE));
        if (searchContextSize != null) {
            options.put(FIELD_SEARCH_CONTEXT_SIZE, searchContextSize);
        }

        JsonNode locationNode = tool.get(FIELD_USER_LOCATION);
        if (locationNode != null && locationNode.isObject()) {
            ObjectNode userLocation = JSON.createObjectNode();
            userLocation.put(FIELD_TYPE, FIELD_APPROXIMATE);
            ObjectNode approximate = JSON.createObjectNode();
            copyTextIfExists(locationNode, approximate, FIELD_COUNTRY);
            copyTextIfExists(locationNode, approximate, FIELD_REGION);
            copyTextIfExists(locationNode, approximate, FIELD_CITY);
            copyTextIfExists(locationNode, approximate, FIELD_TIMEZONE);
            if (!approximate.isEmpty()) {
                userLocation.set(FIELD_APPROXIMATE, approximate);
                options.set(FIELD_USER_LOCATION, userLocation);
            }
        }
        return options;
    }

    private static boolean containsTextValue(JsonNode node, String value) {
        if (node == null || value == null) return false;
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (value.equals(item.asText(null))) return true;
            }
        }
        return false;
    }

    private static boolean isBlankText(JsonNode node) {
        return node == null || !node.isTextual() || node.asText().isBlank();
    }

    private static String textOrDefault(JsonNode node, String defaultValue) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : defaultValue;
    }

    private static boolean hasResponsesFilePayload(JsonNode part) {
        return !isBlankText(part.get(FIELD_FILE_DATA)) || !isBlankText(part.get(FIELD_FILE_ID));
    }

    private static boolean hasChatAudioPayload(JsonNode inputAudio) {
        return !isBlankText(inputAudio.get(FIELD_DATA)) && !isBlankText(inputAudio.get(FIELD_FORMAT));
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

    /**
     * Responses status → Chat finish_reason。
     */
    private static String mapResponsesStatusToChatFinishReason(String status, String incompleteReason, boolean hasToolCalls) {
        if (OpenAiResponsesJsonPolicy.STATUS_INCOMPLETE.equals(status)) {
            return mapResponsesIncompleteReasonToChatFinishReason(incompleteReason);
        }
        if (hasToolCalls) {
            return FINISH_REASON_TOOL_CALLS;
        }
        return FINISH_REASON_STOP;
    }

    private static String mapResponsesIncompleteReasonToChatFinishReason(String incompleteReason) {
        return OpenAiResponsesJsonPolicy.DEFAULT_INCOMPLETE_REASON.equals(incompleteReason)
                ? FINISH_REASON_LENGTH
                : FINISH_REASON_STOP;
    }

    private static String extractReasoningText(JsonNode item) {
        StringBuilder text = new StringBuilder();
        if (item.has(OpenAiResponsesJsonPolicy.FIELD_SUMMARY)
                && item.get(OpenAiResponsesJsonPolicy.FIELD_SUMMARY).isArray()) {
            for (JsonNode summary : item.get(OpenAiResponsesJsonPolicy.FIELD_SUMMARY)) {
                String summaryText = textOrDefault(summary.get(OpenAiResponsesJsonPolicy.FIELD_TEXT), "");
                if (!OpenAiResponsesJsonPolicy.TYPE_SUMMARY_TEXT.equals(
                        textOrDefault(summary.get(OpenAiResponsesJsonPolicy.FIELD_TYPE), ""))
                        || summaryText.isEmpty()) continue;
                if (text.length() > 0) text.append("\n");
                text.append(summaryText);
            }
        }
        return text.toString();
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

    private static boolean isValidTopLogprobs(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt()
                && node.asInt() >= 0 && node.asInt() <= 20;
    }

    private static String normalizeChatReasoningEffort(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        return OpenAiChatCompletionsBodyPolicy.normalizeReasoningEffort(node.asText());
    }

    private static String normalizeVerbosity(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        return OpenAiChatCompletionsBodyPolicy.normalizeTextVerbosity(node.asText());
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

}
