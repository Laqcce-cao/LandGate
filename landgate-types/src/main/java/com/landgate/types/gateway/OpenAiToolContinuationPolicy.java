package com.landgate.types.gateway;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;

/**
 * Sub2API-compatible OpenAI Responses tool-continuation signal extraction.
 *
 * <p>This policy owns only pure JSON signal analysis. It must not parse raw
 * request bodies, mutate requests, choose routes, build auth, write responses,
 * or manage previous-response sessions.</p>
 */
public final class OpenAiToolContinuationPolicy {

    private OpenAiToolContinuationPolicy() {
    }

    public static FunctionCallOutputValidation validateFunctionCallOutputContext(JsonNode root) {
        FunctionCallOutputValidation result = FunctionCallOutputValidation.empty();
        if (root == null || !root.isObject()) {
            return result;
        }
        JsonNode input = root.get(OpenAiResponsesBodyPolicy.FIELD_INPUT);
        if (input == null || !input.isArray()) {
            return result;
        }

        boolean hasFunctionCallOutput = false;
        boolean hasToolCallContext = false;
        for (JsonNode item : input) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String type = text(item.get(OpenAiResponsesJsonPolicy.FIELD_TYPE));
            if (OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL_OUTPUT.equals(type)) {
                hasFunctionCallOutput = true;
            } else if (isToolCallContextType(type)
                    && !text(item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID)).isBlank()) {
                hasToolCallContext = true;
            }
            if (hasFunctionCallOutput && hasToolCallContext) {
                return new FunctionCallOutputValidation(true, true, false, false);
            }
        }

        if (!hasFunctionCallOutput) {
            return result;
        }
        if (hasToolCallContext) {
            return new FunctionCallOutputValidation(true, true, false, false);
        }

        Set<String> callIds = new HashSet<>();
        Set<String> referenceIds = new HashSet<>();
        boolean missingCallId = false;
        for (JsonNode item : input) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String type = text(item.get(OpenAiResponsesJsonPolicy.FIELD_TYPE));
            if (OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL_OUTPUT.equals(type)) {
                String callId = text(item.get(OpenAiResponsesJsonPolicy.FIELD_CALL_ID));
                if (callId.isBlank()) {
                    missingCallId = true;
                } else {
                    callIds.add(callId);
                }
            } else if (OpenAiResponsesJsonPolicy.TYPE_ITEM_REFERENCE.equals(type)) {
                String id = text(item.get(OpenAiResponsesJsonPolicy.FIELD_ID));
                if (!id.isBlank()) {
                    referenceIds.add(id);
                }
            }
        }

        boolean allReferenced = !callIds.isEmpty() && !referenceIds.isEmpty() && referenceIds.containsAll(callIds);
        return new FunctionCallOutputValidation(true, false, missingCallId, allReferenced);
    }

    /**
     * Sub2API-compatible Codex tool continuation signal check.
     *
     * <p>Continuation is needed when a Responses request carries
     * {@code previous_response_id}, explicit tool configuration, tool-choice
     * state, an input tool-call item, an item reference, or a Chat-style
     * {@code role=tool} item that will be converted to
     * {@code function_call_output} before reaching Codex.</p>
     */
    public static boolean needsToolContinuation(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        if (!text(root.get(OpenAiResponsesBodyPolicy.FIELD_PREVIOUS_RESPONSE_ID)).isBlank()) {
            return true;
        }

        JsonNode tools = root.get(OpenAiResponsesBodyPolicy.FIELD_TOOLS);
        if (tools != null && tools.isArray() && !tools.isEmpty()) {
            return true;
        }

        if (hasToolChoiceSignal(root.get(OpenAiResponsesBodyPolicy.FIELD_TOOL_CHOICE))) {
            return true;
        }

        JsonNode input = root.get(OpenAiResponsesBodyPolicy.FIELD_INPUT);
        if (input == null || !input.isArray()) {
            return false;
        }
        for (JsonNode item : input) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String type = text(item.get(OpenAiResponsesJsonPolicy.FIELD_TYPE));
            if (isToolContinuationInputType(type)) {
                return true;
            }
            if (OpenAiChatCompletionsBodyPolicy.ROLE_TOOL.equals(
                    text(item.get(OpenAiResponsesJsonPolicy.FIELD_ROLE)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isToolCallContextType(String type) {
        return OpenAiResponsesJsonPolicy.TYPE_TOOL_CALL.equals(type)
                || OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL.equals(type);
    }

    private static boolean hasToolChoiceSignal(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return !node.asText().trim().isBlank();
        }
        return node.isObject() && !node.isEmpty();
    }

    private static boolean isToolContinuationInputType(String type) {
        return OpenAiResponsesJsonPolicy.TYPE_ITEM_REFERENCE.equals(type)
                || OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL.equals(type)
                || OpenAiResponsesJsonPolicy.TYPE_TOOL_CALL.equals(type)
                || OpenAiResponsesJsonPolicy.TYPE_CUSTOM_TOOL_CALL.equals(type)
                || OpenAiResponsesJsonPolicy.TYPE_FUNCTION_CALL_OUTPUT.equals(type)
                || OpenAiResponsesJsonPolicy.TYPE_MCP_TOOL_CALL.equals(type)
                || OpenAiResponsesJsonPolicy.TYPE_MCP_TOOL_CALL_OUTPUT.equals(type)
                || OpenAiResponsesJsonPolicy.TYPE_CUSTOM_TOOL_CALL_OUTPUT.equals(type)
                || OpenAiResponsesJsonPolicy.TYPE_TOOL_SEARCH_CALL.equals(type)
                || OpenAiResponsesJsonPolicy.TYPE_TOOL_SEARCH_OUTPUT.equals(type)
                || OpenAiResponsesJsonPolicy.TYPE_LOCAL_SHELL_CALL.equals(type);
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText().trim() : "";
    }

    public record FunctionCallOutputValidation(
            boolean hasFunctionCallOutput,
            boolean hasToolCallContext,
            boolean hasFunctionCallOutputMissingCallId,
            boolean hasItemReferenceForAllCallIds
    ) {
        public static FunctionCallOutputValidation empty() {
            return new FunctionCallOutputValidation(false, false, false, false);
        }
    }
}
