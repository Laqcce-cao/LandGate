package com.landgate.trigger.gateway.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.types.gateway.OpenAiPreviousResponseIdPolicy;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;
import com.landgate.types.gateway.OpenAiResponsesHttpRequestPolicy;
import com.landgate.types.gateway.OpenAiToolContinuationPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Validates client-facing HTTP OpenAI Responses requests before route selection.
 *
 * <p>This validator only applies to client requests whose URL resolved to the
 * Responses format. It intentionally does not run on translated Anthropic
 * Messages compatibility bodies, because those may receive an internal
 * {@code previous_response_id} for API-key continuation.</p>
 */
@Slf4j
@Service
public class OpenAiResponsesHttpRequestValidator {

    private static final ObjectMapper JSON = new ObjectMapper();

    public ValidationResult validate(String body, String requestFormat) {
        if (!OpenAiResponsesHttpRequestPolicy.appliesToClientFormat(requestFormat)) {
            return ValidationResult.acceptedResult();
        }
        if (body == null || body.isEmpty()) {
            return ValidationResult.rejected(
                    OpenAiResponsesHttpRequestPolicy.STATUS_BAD_REQUEST,
                    OpenAiResponsesHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST,
                    OpenAiResponsesHttpRequestPolicy.MESSAGE_EMPTY_BODY,
                    OpenAiPreviousResponseIdPolicy.KIND_EMPTY);
        }
        JsonNode root = parseBody(body);
        if (root == null || !root.isObject()) {
            return ValidationResult.rejected(
                    OpenAiResponsesHttpRequestPolicy.STATUS_BAD_REQUEST,
                    OpenAiResponsesHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST,
                    OpenAiResponsesHttpRequestPolicy.MESSAGE_PARSE_BODY_FAILED,
                    OpenAiPreviousResponseIdPolicy.KIND_EMPTY);
        }
        JsonNode model = root.get(OpenAiResponsesBodyPolicy.FIELD_MODEL);
        if (model == null || !model.isTextual() || model.asText().isBlank()) {
            return ValidationResult.rejected(
                    OpenAiResponsesHttpRequestPolicy.STATUS_BAD_REQUEST,
                    OpenAiResponsesHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST,
                    OpenAiResponsesHttpRequestPolicy.MESSAGE_MODEL_REQUIRED,
                    OpenAiPreviousResponseIdPolicy.KIND_EMPTY);
        }
        JsonNode stream = root.get(OpenAiResponsesBodyPolicy.FIELD_STREAM);
        if (stream != null && !stream.isBoolean()) {
            return ValidationResult.rejected(
                    OpenAiResponsesHttpRequestPolicy.STATUS_BAD_REQUEST,
                    OpenAiResponsesHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST,
                    OpenAiResponsesHttpRequestPolicy.MESSAGE_INVALID_STREAM_TYPE,
                    OpenAiPreviousResponseIdPolicy.KIND_EMPTY);
        }
        String previousResponseId = extractPreviousResponseId(root);
        String kind = OpenAiPreviousResponseIdPolicy.classify(previousResponseId);
        if (OpenAiPreviousResponseIdPolicy.shouldRejectHttpResponsesPreviousResponseId(previousResponseId)) {
            return ValidationResult.rejected(
                    OpenAiResponsesHttpRequestPolicy.STATUS_BAD_REQUEST,
                    OpenAiResponsesHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST,
                    OpenAiPreviousResponseIdPolicy.httpResponsesPreviousResponseIdMessage(previousResponseId),
                    kind);
        }
        OpenAiToolContinuationPolicy.FunctionCallOutputValidation toolContinuation =
                OpenAiToolContinuationPolicy.validateFunctionCallOutputContext(root);
        if (!toolContinuation.hasFunctionCallOutput()) {
            return ValidationResult.acceptedResult();
        }
        if (toolContinuation.hasToolCallContext()) {
            return ValidationResult.acceptedResult();
        }
        if (toolContinuation.hasFunctionCallOutputMissingCallId()) {
            return ValidationResult.rejected(
                    OpenAiResponsesHttpRequestPolicy.STATUS_BAD_REQUEST,
                    OpenAiResponsesHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST,
                    OpenAiResponsesHttpRequestPolicy.MESSAGE_FUNCTION_CALL_OUTPUT_REQUIRES_CALL_ID,
                    kind);
        }
        if (toolContinuation.hasItemReferenceForAllCallIds()) {
            return ValidationResult.acceptedResult();
        }
        return ValidationResult.rejected(
                OpenAiResponsesHttpRequestPolicy.STATUS_BAD_REQUEST,
                OpenAiResponsesHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST,
                OpenAiResponsesHttpRequestPolicy.MESSAGE_FUNCTION_CALL_OUTPUT_REQUIRES_ITEM_REFERENCE,
                kind);
    }

    private static JsonNode parseBody(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            log.debug("Failed to parse Responses request body while validating HTTP request");
            return null;
        }
    }

    private static String extractPreviousResponseId(JsonNode root) {
        JsonNode value = root.get(OpenAiResponsesBodyPolicy.FIELD_PREVIOUS_RESPONSE_ID);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isValueNode() ? value.asText("") : value.toString();
    }

    public record ValidationResult(
            boolean accepted,
            int status,
            String code,
            String message,
            String previousResponseIdKind
    ) {
        public static ValidationResult acceptedResult() {
            return new ValidationResult(true, 0, "", "", OpenAiPreviousResponseIdPolicy.KIND_EMPTY);
        }

        public static ValidationResult rejected(int status, String code, String message, String previousResponseIdKind) {
            return new ValidationResult(false, status, code, message, previousResponseIdKind);
        }
    }
}
