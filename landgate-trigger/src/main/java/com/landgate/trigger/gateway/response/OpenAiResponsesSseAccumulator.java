package com.landgate.trigger.gateway.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.types.gateway.OpenAiResponsesSsePolicy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.landgate.types.gateway.OpenAiResponsesJsonPolicy.*;

/**
 * Accumulates OpenAI Responses SSE events into a non-streaming Responses JSON body.
 *
 * <p>This class is intentionally scoped to Responses SSE aggregation. It does
 * not read HTTP streams, write servlet responses, select routes, translate
 * protocols, parse usage, or bill usage.</p>
 */
final class OpenAiResponsesSseAccumulator {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final int CONTENT_KEY_OUTPUT_INDEX_MULTIPLIER = 10_000;

    private final Map<Integer, ObjectNode> outputItems = new LinkedHashMap<>();
    private final Map<Integer, StringBuilder> textByContentKey = new LinkedHashMap<>();
    private final Map<Integer, String> contentTypeByContentKey = new LinkedHashMap<>();
    private final Map<Integer, StringBuilder> argumentsByOutputIndex = new LinkedHashMap<>();
    private final Map<Integer, StringBuilder> reasoningSummaryByOutputIndex = new LinkedHashMap<>();
    private final Map<Integer, StringBuilder> reasoningTextByOutputIndex = new LinkedHashMap<>();

    private String responseId = RESPONSE_ID_PREFIX
            + UUID.randomUUID().toString().replace("-", "").substring(0, RESPONSE_ID_RANDOM_LENGTH);
    private String responseModel;
    private String status = STATUS_COMPLETED;
    private String incompleteReason;
    private ObjectNode errorNode;
    private boolean terminalSeen;
    private boolean finalResponseSeen;
    private boolean terminalResponseSeen;
    private boolean terminalResponseOutputPopulated;

    OpenAiResponsesSseAccumulator(String responseModel) {
        this.responseModel = responseModel == null || responseModel.isBlank() ? DEFAULT_MODEL : responseModel;
    }

    boolean process(JsonNode event) {
        if (event == null || !event.isObject()) {
            return false;
        }
        String type = event.path(FIELD_TYPE).asText("");
        if (OpenAiResponsesSsePolicy.EVENT_RESPONSE_CREATED.equals(type) && event.has(FIELD_RESPONSE)) {
            JsonNode resp = event.get(FIELD_RESPONSE);
            if (resp.has(FIELD_ID)) responseId = resp.get(FIELD_ID).asText(responseId);
            if (resp.has(FIELD_MODEL)) responseModel = resp.get(FIELD_MODEL).asText(responseModel);
        } else if (OpenAiResponsesSsePolicy.EVENT_OUTPUT_ITEM_ADDED.equals(type) && event.has(FIELD_ITEM)) {
            int outputIndex = event.path(FIELD_OUTPUT_INDEX).asInt(outputItems.size());
            outputItems.put(outputIndex, normalizeStreamingOutputItem(event.get(FIELD_ITEM)));
        } else if (OpenAiResponsesSsePolicy.EVENT_OUTPUT_ITEM_DONE.equals(type) && event.has(FIELD_ITEM)) {
            int outputIndex = event.path(FIELD_OUTPUT_INDEX).asInt(outputItems.size());
            JsonNode item = event.get(FIELD_ITEM);
            outputItems.put(outputIndex, normalizeStreamingOutputItem(item));
            mergeFinalOutputItem(outputIndex, item);
        } else if (OpenAiResponsesSsePolicy.EVENT_OUTPUT_TEXT_DELTA.equals(type)) {
            int outputIndex = event.path(FIELD_OUTPUT_INDEX).asInt(0);
            int contentIndex = event.path(FIELD_CONTENT_INDEX).asInt(0);
            int contentKey = contentKey(outputIndex, contentIndex);
            contentTypeByContentKey.put(contentKey, TYPE_OUTPUT_TEXT);
            textByContentKey.computeIfAbsent(contentKey, ignored -> new StringBuilder())
                    .append(event.path(FIELD_DELTA).asText(""));
        } else if (OpenAiResponsesSsePolicy.EVENT_OUTPUT_TEXT_DONE.equals(type)) {
            int outputIndex = event.path(FIELD_OUTPUT_INDEX).asInt(0);
            int contentIndex = event.path(FIELD_CONTENT_INDEX).asInt(0);
            String text = event.path(FIELD_TEXT).asText("");
            if (!text.isEmpty()) {
                int contentKey = contentKey(outputIndex, contentIndex);
                contentTypeByContentKey.put(contentKey, TYPE_OUTPUT_TEXT);
                StringBuilder builder = textByContentKey.computeIfAbsent(contentKey, ignored -> new StringBuilder());
                builder.setLength(0);
                builder.append(text);
            }
        } else if (OpenAiResponsesSsePolicy.EVENT_CONTENT_PART_DONE.equals(type)) {
            JsonNode part = event.path(FIELD_PART);
            String partType = part.path(FIELD_TYPE).asText("");
            if (isTextOutputPart(partType)) {
                int outputIndex = event.path(FIELD_OUTPUT_INDEX).asInt(0);
                int contentIndex = event.path(FIELD_CONTENT_INDEX).asInt(0);
                String text = TYPE_REFUSAL.equals(partType)
                        ? part.path(FIELD_REFUSAL).asText("")
                        : part.path(FIELD_TEXT).asText("");
                int contentKey = contentKey(outputIndex, contentIndex);
                contentTypeByContentKey.put(contentKey, partType);
                StringBuilder builder = textByContentKey.computeIfAbsent(contentKey, ignored -> new StringBuilder());
                builder.setLength(0);
                builder.append(text);
            }
        } else if (OpenAiResponsesSsePolicy.EVENT_REFUSAL_DELTA.equals(type)) {
            int outputIndex = event.path(FIELD_OUTPUT_INDEX).asInt(0);
            int contentIndex = event.path(FIELD_CONTENT_INDEX).asInt(0);
            int contentKey = contentKey(outputIndex, contentIndex);
            contentTypeByContentKey.put(contentKey, TYPE_REFUSAL);
            textByContentKey.computeIfAbsent(contentKey, ignored -> new StringBuilder())
                    .append(event.path(FIELD_DELTA).asText(""));
        } else if (OpenAiResponsesSsePolicy.EVENT_REFUSAL_DONE.equals(type)) {
            int outputIndex = event.path(FIELD_OUTPUT_INDEX).asInt(0);
            int contentIndex = event.path(FIELD_CONTENT_INDEX).asInt(0);
            String refusal = event.path(FIELD_REFUSAL).asText("");
            if (!refusal.isEmpty()) {
                int contentKey = contentKey(outputIndex, contentIndex);
                contentTypeByContentKey.put(contentKey, TYPE_REFUSAL);
                StringBuilder builder = textByContentKey.computeIfAbsent(contentKey, ignored -> new StringBuilder());
                builder.setLength(0);
                builder.append(refusal);
            }
        } else if (OpenAiResponsesSsePolicy.EVENT_FUNCTION_CALL_ARGUMENTS_DELTA.equals(type)) {
            int outputIndex = event.path(FIELD_OUTPUT_INDEX).asInt(0);
            argumentsByOutputIndex.computeIfAbsent(outputIndex, ignored -> new StringBuilder())
                    .append(event.path(FIELD_DELTA).asText(""));
        } else if (OpenAiResponsesSsePolicy.EVENT_FUNCTION_CALL_ARGUMENTS_DONE.equals(type)) {
            int outputIndex = event.path(FIELD_OUTPUT_INDEX).asInt(0);
            String arguments = event.path(FIELD_ARGUMENTS).asText("");
            if (!arguments.isEmpty()) {
                argumentsByOutputIndex.computeIfAbsent(outputIndex, ignored -> new StringBuilder()).setLength(0);
                argumentsByOutputIndex.get(outputIndex).append(arguments);
            }
        } else if (OpenAiResponsesSsePolicy.EVENT_REASONING_SUMMARY_TEXT_DELTA.equals(type)) {
            int outputIndex = event.path(FIELD_OUTPUT_INDEX).asInt(0);
            reasoningSummaryByOutputIndex.computeIfAbsent(outputIndex, ignored -> new StringBuilder())
                    .append(event.path(FIELD_DELTA).asText(""));
        } else if (OpenAiResponsesSsePolicy.EVENT_REASONING_SUMMARY_TEXT_DONE.equals(type)) {
            int outputIndex = event.path(FIELD_OUTPUT_INDEX).asInt(0);
            String text = event.path(FIELD_TEXT).asText("");
            if (!text.isEmpty()) {
                StringBuilder builder = reasoningSummaryByOutputIndex.computeIfAbsent(outputIndex,
                        ignored -> new StringBuilder());
                builder.setLength(0);
                builder.append(text);
            }
        } else if (OpenAiResponsesSsePolicy.EVENT_REASONING_TEXT_DELTA.equals(type)) {
            int outputIndex = event.path(FIELD_OUTPUT_INDEX).asInt(0);
            reasoningTextByOutputIndex.computeIfAbsent(outputIndex, ignored -> new StringBuilder())
                    .append(event.path(FIELD_DELTA).asText(""));
        } else if (OpenAiResponsesSsePolicy.EVENT_REASONING_TEXT_DONE.equals(type)) {
            int outputIndex = event.path(FIELD_OUTPUT_INDEX).asInt(0);
            String text = event.path(FIELD_TEXT).asText("");
            if (!text.isEmpty()) {
                StringBuilder builder = reasoningTextByOutputIndex.computeIfAbsent(outputIndex,
                        ignored -> new StringBuilder());
                builder.setLength(0);
                builder.append(text);
            }
        } else if (OpenAiResponsesSsePolicy.EVENT_RESPONSE_COMPLETED.equals(type)
                || OpenAiResponsesSsePolicy.EVENT_RESPONSE_DONE.equals(type)) {
            terminalSeen = true;
            if (event.has(FIELD_RESPONSE)) {
                finalResponseSeen = true;
                mergeTerminalResponse(event.get(FIELD_RESPONSE));
            }
            return true;
        } else if (OpenAiResponsesSsePolicy.EVENT_RESPONSE_INCOMPLETE.equals(type)) {
            terminalSeen = true;
            status = STATUS_INCOMPLETE;
            incompleteReason = DEFAULT_INCOMPLETE_REASON;
            if (event.has(FIELD_RESPONSE)) {
                mergeTerminalResponse(event.get(FIELD_RESPONSE));
                status = STATUS_INCOMPLETE;
                incompleteReason = event.get(FIELD_RESPONSE)
                        .path(FIELD_INCOMPLETE_DETAILS)
                        .path(FIELD_REASON)
                        .asText(DEFAULT_INCOMPLETE_REASON);
            }
            return true;
        } else if (OpenAiResponsesSsePolicy.EVENT_RESPONSE_FAILED.equals(type)) {
            terminalSeen = true;
            status = STATUS_FAILED;
            if (event.has(FIELD_RESPONSE) && event.get(FIELD_RESPONSE).has(FIELD_ERROR)
                    && event.get(FIELD_RESPONSE).get(FIELD_ERROR).isObject()) {
                errorNode = (ObjectNode) event.get(FIELD_RESPONSE).get(FIELD_ERROR).deepCopy();
            }
            if (event.has(FIELD_RESPONSE)) {
                mergeTerminalResponse(event.get(FIELD_RESPONSE));
                status = STATUS_FAILED;
            }
            return true;
        } else if (OpenAiResponsesSsePolicy.EVENT_RESPONSE_CANCELLED.equals(type)
                || OpenAiResponsesSsePolicy.EVENT_RESPONSE_CANCELED.equals(type)) {
            terminalSeen = true;
            status = STATUS_FAILED;
            if (event.has(FIELD_RESPONSE)) {
                JsonNode response = event.get(FIELD_RESPONSE);
                if (response.has(FIELD_ERROR) && response.get(FIELD_ERROR).isObject()) {
                    errorNode = (ObjectNode) response.get(FIELD_ERROR).deepCopy();
                }
                mergeTerminalResponse(response);
                status = STATUS_FAILED;
            }
            return true;
        }
        return false;
    }

    boolean terminalSeen() {
        return terminalSeen;
    }

    boolean finalResponseSeen() {
        return finalResponseSeen;
    }

    boolean terminalResponseSeen() {
        return terminalResponseSeen;
    }

    String responseId() {
        return responseId;
    }

    String buildResponsesJson(UsageTokens usage) throws IOException {
        return buildResponsesJson(usage, true);
    }

    String buildResponsesJson(UsageTokens usage, boolean addCachedTokensToInput) throws IOException {
        supplementEmptyTerminalOutputFromDeltas();

        Map<Integer, ArrayNode> contentByOutputIndex = new LinkedHashMap<>();
        textByContentKey.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    int outputIndex = outputIndexFromContentKey(entry.getKey());
                    String partType = contentTypeByContentKey.getOrDefault(entry.getKey(), TYPE_OUTPUT_TEXT);
                    ObjectNode text = JSON_MAPPER.createObjectNode();
                    text.put(FIELD_TYPE, partType);
                    if (TYPE_REFUSAL.equals(partType)) {
                        text.put(FIELD_REFUSAL, entry.getValue().toString());
                    } else {
                        text.put(FIELD_TEXT, entry.getValue().toString());
                    }
                    contentByOutputIndex.computeIfAbsent(outputIndex, ignored -> JSON_MAPPER.createArrayNode())
                            .add(text);
                });

        for (Map.Entry<Integer, ArrayNode> entry : contentByOutputIndex.entrySet()) {
            ObjectNode item = outputItems.computeIfAbsent(entry.getKey(), ignored -> {
                ObjectNode message = JSON_MAPPER.createObjectNode();
                message.put(FIELD_TYPE, TYPE_MESSAGE);
                message.put(FIELD_ROLE, ROLE_ASSISTANT);
                message.put(FIELD_STATUS, STATUS_COMPLETED);
                message.set(FIELD_CONTENT, JSON_MAPPER.createArrayNode());
                return message;
            });
            if (TYPE_MESSAGE.equals(item.path(FIELD_TYPE).asText())) {
                item.set(FIELD_CONTENT, entry.getValue());
            }
        }

        for (Map.Entry<Integer, StringBuilder> entry : argumentsByOutputIndex.entrySet()) {
            ObjectNode item = outputItems.get(entry.getKey());
            if (item != null && TYPE_FUNCTION_CALL.equals(item.path(FIELD_TYPE).asText())) {
                String arguments = entry.getValue().toString();
                item.put(FIELD_ARGUMENTS, arguments.isEmpty() ? EMPTY_JSON_OBJECT : arguments);
                item.put(FIELD_STATUS, STATUS_COMPLETED);
            }
        }

        for (Map.Entry<Integer, StringBuilder> entry : reasoningSummaryByOutputIndex.entrySet()) {
            ObjectNode item = ensureReasoningItem(entry.getKey());
            if (item == null) continue;
            item.set(FIELD_SUMMARY, reasoningTextArray(TYPE_SUMMARY_TEXT, entry.getValue().toString()));
            item.put(FIELD_STATUS, STATUS_COMPLETED);
        }

        for (Map.Entry<Integer, StringBuilder> entry : reasoningTextByOutputIndex.entrySet()) {
            ObjectNode item = ensureReasoningItem(entry.getKey());
            if (item == null) continue;
            item.set(FIELD_CONTENT, reasoningTextArray(TYPE_REASONING_TEXT, entry.getValue().toString()));
            item.put(FIELD_STATUS, STATUS_COMPLETED);
        }

        if (outputItems.isEmpty()) {
            ObjectNode message = JSON_MAPPER.createObjectNode();
            message.put(FIELD_TYPE, TYPE_MESSAGE);
            message.put(FIELD_ROLE, ROLE_ASSISTANT);
            message.put(FIELD_STATUS, STATUS_COMPLETED);
            ArrayNode content = JSON_MAPPER.createArrayNode();
            ObjectNode text = JSON_MAPPER.createObjectNode();
            text.put(FIELD_TYPE, TYPE_OUTPUT_TEXT);
            text.put(FIELD_TEXT, "");
            content.add(text);
            message.set(FIELD_CONTENT, content);
            outputItems.put(0, message);
        }

        ObjectNode root = JSON_MAPPER.createObjectNode();
        root.put(FIELD_ID, responseId);
        root.put(FIELD_OBJECT, OBJECT_RESPONSE);
        root.put(FIELD_MODEL, responseModel);
        root.put(FIELD_STATUS, status == null || status.isBlank() ? STATUS_COMPLETED : status);
        if (isIncompleteStatus(status)) {
            ObjectNode details = JSON_MAPPER.createObjectNode();
            details.put(FIELD_REASON, incompleteReason == null || incompleteReason.isBlank()
                    ? DEFAULT_INCOMPLETE_REASON
                    : incompleteReason);
            root.set(FIELD_INCOMPLETE_DETAILS, details);
        }
        if (isFailedStatus(status) && errorNode != null) {
            root.set(FIELD_ERROR, errorNode);
        }
        ArrayNode output = JSON_MAPPER.createArrayNode();
        outputItems.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> output.add(entry.getValue()));
        root.set(FIELD_OUTPUT, output);

        ObjectNode usageNode = JSON_MAPPER.createObjectNode();
        int inputTokens = usage != null ? usage.getInputTokens() : 0;
        int cachedTokens = usage != null ? usage.getCacheReadTokens() : 0;
        int outputTokens = usage != null ? usage.getOutputTokens() : 0;
        int responseInputTokens = addCachedTokensToInput ? inputTokens + cachedTokens : inputTokens;
        usageNode.put(FIELD_INPUT_TOKENS, responseInputTokens);
        usageNode.put(FIELD_OUTPUT_TOKENS, outputTokens);
        usageNode.put(FIELD_TOTAL_TOKENS, responseInputTokens + outputTokens);
        if (cachedTokens > 0) {
            ObjectNode inputDetails = JSON_MAPPER.createObjectNode();
            inputDetails.put(FIELD_CACHED_TOKENS, cachedTokens);
            usageNode.set(FIELD_INPUT_TOKENS_DETAILS, inputDetails);
        }
        root.set(FIELD_USAGE, usageNode);

        return JSON_MAPPER.writeValueAsString(root);
    }

    private void mergeTerminalResponse(JsonNode resp) {
        if (resp == null || !resp.isObject()) return;
        terminalResponseSeen = true;
        if (resp.has(FIELD_ID)) responseId = resp.get(FIELD_ID).asText(responseId);
        if (resp.has(FIELD_MODEL)) responseModel = resp.get(FIELD_MODEL).asText(responseModel);

        String responseStatus = resp.path(FIELD_STATUS).asText("");
        if (isIncompleteStatus(responseStatus)) {
            status = STATUS_INCOMPLETE;
            incompleteReason = resp.path(FIELD_INCOMPLETE_DETAILS)
                    .path(FIELD_REASON)
                    .asText(DEFAULT_INCOMPLETE_REASON);
        } else if (isFailedStatus(responseStatus)) {
            status = STATUS_FAILED;
        } else if (STATUS_COMPLETED.equals(responseStatus)) {
            status = STATUS_COMPLETED;
        }

        mergeFinalResponseOutput(resp.path(FIELD_OUTPUT));
    }

    private static ObjectNode normalizeStreamingOutputItem(JsonNode item) {
        ObjectNode normalized = JSON_MAPPER.createObjectNode();
        String type = item.path(FIELD_TYPE).asText(TYPE_MESSAGE);
        normalized.put(FIELD_TYPE, type);
        if (item.has(FIELD_ID)) normalized.set(FIELD_ID, item.get(FIELD_ID));
        if (TYPE_FUNCTION_CALL.equals(type)) {
            normalized.put(FIELD_CALL_ID, item.path(FIELD_CALL_ID).asText(""));
            normalized.put(FIELD_NAME, item.path(FIELD_NAME).asText(""));
            normalized.put(FIELD_ARGUMENTS, item.path(FIELD_ARGUMENTS).asText(EMPTY_JSON_OBJECT));
            normalized.put(FIELD_STATUS, STATUS_COMPLETED);
        } else if (TYPE_WEB_SEARCH_CALL.equals(type)) {
            normalized.put(FIELD_STATUS, item.path(FIELD_STATUS).asText(STATUS_COMPLETED));
            if (item.has(FIELD_ACTION) && item.get(FIELD_ACTION).isObject()) {
                normalized.set(FIELD_ACTION, item.get(FIELD_ACTION).deepCopy());
            }
        } else if (TYPE_REASONING.equals(type)) {
            normalized.put(FIELD_STATUS, STATUS_COMPLETED);
            normalized.set(FIELD_SUMMARY, item.has(FIELD_SUMMARY) ? item.get(FIELD_SUMMARY) : JSON_MAPPER.createArrayNode());
            normalized.set(FIELD_CONTENT, item.has(FIELD_CONTENT) ? item.get(FIELD_CONTENT) : JSON_MAPPER.createArrayNode());
        } else {
            normalized.put(FIELD_TYPE, TYPE_MESSAGE);
            normalized.put(FIELD_ROLE, item.path(FIELD_ROLE).asText(ROLE_ASSISTANT));
            normalized.put(FIELD_STATUS, STATUS_COMPLETED);
            normalized.set(FIELD_CONTENT, JSON_MAPPER.createArrayNode());
        }
        return normalized;
    }

    private void mergeFinalResponseOutput(JsonNode output) {
        if (output == null || !output.isArray()) return;
        if (!output.isEmpty()) {
            terminalResponseOutputPopulated = true;
        }
        for (int i = 0; i < output.size(); i++) {
            JsonNode item = output.get(i);
            outputItems.put(i, normalizeStreamingOutputItem(item));
            mergeFinalOutputItem(i, item);
        }
    }

    private void supplementEmptyTerminalOutputFromDeltas() {
        if (terminalResponseOutputPopulated || hasWebSearchOutputItem() || !hasBufferedDeltaContent()) {
            return;
        }

        LinkedHashMap<Integer, ObjectNode> supplemented = new LinkedHashMap<>();
        int outputIndex = 0;

        String reasoningSummary = bufferedReasoningSummaryText();
        String reasoningText = bufferedReasoningText();
        if (!reasoningSummary.isEmpty() || !reasoningText.isEmpty()) {
            ObjectNode item = createReasoningItem();
            if (!reasoningSummary.isEmpty()) {
                item.set(FIELD_SUMMARY, reasoningTextArray(TYPE_SUMMARY_TEXT, reasoningSummary));
            }
            if (!reasoningText.isEmpty()) {
                item.set(FIELD_CONTENT, reasoningTextArray(TYPE_REASONING_TEXT, reasoningText));
            }
            supplemented.put(outputIndex++, item);
        }

        ArrayNode messageContent = bufferedMessageContent();
        if (!messageContent.isEmpty()) {
            ObjectNode item = JSON_MAPPER.createObjectNode();
            item.put(FIELD_TYPE, TYPE_MESSAGE);
            item.put(FIELD_ROLE, ROLE_ASSISTANT);
            item.put(FIELD_STATUS, STATUS_COMPLETED);
            item.set(FIELD_CONTENT, messageContent);
            supplemented.put(outputIndex++, item);
        }

        for (Map.Entry<Integer, ObjectNode> entry : outputItems.entrySet()) {
            ObjectNode item = entry.getValue();
            String type = item.path(FIELD_TYPE).asText("");
            if (!TYPE_FUNCTION_CALL.equals(type)) {
                continue;
            }
            ObjectNode copy = item.deepCopy();
            String arguments = argumentsByOutputIndex.getOrDefault(entry.getKey(), new StringBuilder()).toString();
            copy.put(FIELD_ARGUMENTS, arguments.isEmpty() ? EMPTY_JSON_OBJECT : arguments);
            copy.put(FIELD_STATUS, STATUS_COMPLETED);
            supplemented.put(outputIndex++, copy);
        }

        if (!supplemented.isEmpty()) {
            outputItems.clear();
            outputItems.putAll(supplemented);
            textByContentKey.clear();
            contentTypeByContentKey.clear();
            argumentsByOutputIndex.clear();
            reasoningSummaryByOutputIndex.clear();
            reasoningTextByOutputIndex.clear();
        }
    }

    private boolean hasBufferedDeltaContent() {
        return textByContentKey.values().stream().anyMatch(builder -> builder.length() > 0)
                || reasoningSummaryByOutputIndex.values().stream().anyMatch(builder -> builder.length() > 0)
                || reasoningTextByOutputIndex.values().stream().anyMatch(builder -> builder.length() > 0)
                || outputItems.values().stream().anyMatch(item -> TYPE_FUNCTION_CALL.equals(item.path(FIELD_TYPE).asText("")));
    }

    private boolean hasWebSearchOutputItem() {
        return outputItems.values().stream()
                .anyMatch(item -> TYPE_WEB_SEARCH_CALL.equals(item.path(FIELD_TYPE).asText("")));
    }

    private String bufferedReasoningSummaryText() {
        StringBuilder reasoning = new StringBuilder();
        reasoningSummaryByOutputIndex.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> reasoning.append(entry.getValue()));
        return reasoning.toString();
    }

    private String bufferedReasoningText() {
        StringBuilder reasoning = new StringBuilder();
        reasoningTextByOutputIndex.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> reasoning.append(entry.getValue()));
        return reasoning.toString();
    }

    private ArrayNode bufferedMessageContent() {
        ArrayNode content = JSON_MAPPER.createArrayNode();
        textByContentKey.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (entry.getValue().isEmpty()) {
                        return;
                    }
                    String partType = contentTypeByContentKey.getOrDefault(entry.getKey(), TYPE_OUTPUT_TEXT);
                    ObjectNode part = JSON_MAPPER.createObjectNode();
                    part.put(FIELD_TYPE, partType);
                    if (TYPE_REFUSAL.equals(partType)) {
                        part.put(FIELD_REFUSAL, entry.getValue().toString());
                    } else {
                        part.put(FIELD_TEXT, entry.getValue().toString());
                    }
                    content.add(part);
                });
        return content;
    }

    private void mergeFinalOutputItem(int outputIndex, JsonNode item) {
        String itemType = item.path(FIELD_TYPE).asText("");
        if (TYPE_MESSAGE.equals(itemType) && item.has(FIELD_CONTENT) && item.get(FIELD_CONTENT).isArray()) {
            int contentIndex = 0;
            for (JsonNode part : item.get(FIELD_CONTENT)) {
                String partType = part.path(FIELD_TYPE).asText("");
                if (isTextOutputPart(partType)) {
                    String text = TYPE_REFUSAL.equals(partType)
                            ? part.path(FIELD_REFUSAL).asText("")
                            : part.path(FIELD_TEXT).asText("");
                    int key = contentKey(outputIndex, contentIndex);
                    contentTypeByContentKey.put(key, partType);
                    StringBuilder builder = textByContentKey.computeIfAbsent(key, ignored -> new StringBuilder());
                    builder.setLength(0);
                    builder.append(text);
                }
                contentIndex++;
            }
        } else if (TYPE_FUNCTION_CALL.equals(itemType)) {
            if (item.has(FIELD_ARGUMENTS)) {
                StringBuilder builder = argumentsByOutputIndex.computeIfAbsent(outputIndex, ignored -> new StringBuilder());
                builder.setLength(0);
                builder.append(item.path(FIELD_ARGUMENTS).asText(EMPTY_JSON_OBJECT));
            }
        } else if (TYPE_REASONING.equals(itemType)) {
            if (item.has(FIELD_SUMMARY) && item.get(FIELD_SUMMARY).isArray()) {
                StringBuilder builder = reasoningSummaryByOutputIndex.computeIfAbsent(outputIndex,
                        ignored -> new StringBuilder());
                builder.setLength(0);
                for (JsonNode summary : item.get(FIELD_SUMMARY)) {
                    if (!TYPE_SUMMARY_TEXT.equals(summary.path(FIELD_TYPE).asText(""))) continue;
                    if (builder.length() > 0) builder.append("\n");
                    builder.append(summary.path(FIELD_TEXT).asText(""));
                }
            }
            if (item.has(FIELD_CONTENT) && item.get(FIELD_CONTENT).isArray()) {
                StringBuilder builder = reasoningTextByOutputIndex.computeIfAbsent(outputIndex,
                        ignored -> new StringBuilder());
                builder.setLength(0);
                for (JsonNode content : item.get(FIELD_CONTENT)) {
                    if (!TYPE_REASONING_TEXT.equals(content.path(FIELD_TYPE).asText(""))) continue;
                    if (builder.length() > 0) builder.append("\n");
                    builder.append(content.path(FIELD_TEXT).asText(""));
                }
            }
        }
    }

    private ObjectNode ensureReasoningItem(int outputIndex) {
        ObjectNode item = outputItems.computeIfAbsent(outputIndex, ignored -> createReasoningItem());
        return TYPE_REASONING.equals(item.path(FIELD_TYPE).asText()) ? item : null;
    }

    private static ObjectNode createReasoningItem() {
        ObjectNode reasoning = JSON_MAPPER.createObjectNode();
        reasoning.put(FIELD_TYPE, TYPE_REASONING);
        reasoning.put(FIELD_STATUS, STATUS_COMPLETED);
        reasoning.set(FIELD_SUMMARY, JSON_MAPPER.createArrayNode());
        reasoning.set(FIELD_CONTENT, JSON_MAPPER.createArrayNode());
        return reasoning;
    }

    private static ArrayNode reasoningTextArray(String type, String textValue) {
        ArrayNode values = JSON_MAPPER.createArrayNode();
        ObjectNode text = JSON_MAPPER.createObjectNode();
        text.put(FIELD_TYPE, type);
        text.put(FIELD_TEXT, textValue);
        values.add(text);
        return values;
    }

    private static int contentKey(int outputIndex, int contentIndex) {
        return outputIndex * CONTENT_KEY_OUTPUT_INDEX_MULTIPLIER + contentIndex;
    }

    private static int outputIndexFromContentKey(int contentKey) {
        return contentKey / CONTENT_KEY_OUTPUT_INDEX_MULTIPLIER;
    }
}
