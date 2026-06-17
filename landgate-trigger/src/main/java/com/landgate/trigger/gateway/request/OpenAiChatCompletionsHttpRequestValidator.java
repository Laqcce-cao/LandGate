package com.landgate.trigger.gateway.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.types.gateway.OpenAiChatCompletionsBodyPolicy;
import com.landgate.types.gateway.OpenAiChatCompletionsHttpRequestPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Validates client-facing HTTP OpenAI Chat Completions requests before route selection.
 *
 * <p>This validator intentionally mirrors Sub2API's Chat Completions HTTP
 * handler. It does not apply Responses-only validation such as boolean stream
 * type checks or previous_response_id restrictions.</p>
 */
@Slf4j
@Service
public class OpenAiChatCompletionsHttpRequestValidator {

    private static final ObjectMapper JSON = new ObjectMapper();

    public ValidationResult validate(String body, String requestFormat) {
        if (!OpenAiChatCompletionsHttpRequestPolicy.appliesToClientFormat(requestFormat)) {
            return ValidationResult.acceptedResult();
        }
        if (body == null || body.isEmpty()) {
            return ValidationResult.rejected(
                    OpenAiChatCompletionsHttpRequestPolicy.STATUS_BAD_REQUEST,
                    OpenAiChatCompletionsHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST,
                    OpenAiChatCompletionsHttpRequestPolicy.MESSAGE_EMPTY_BODY);
        }
        JsonNode root = parseBody(body);
        if (root == null || !root.isObject()) {
            return ValidationResult.rejected(
                    OpenAiChatCompletionsHttpRequestPolicy.STATUS_BAD_REQUEST,
                    OpenAiChatCompletionsHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST,
                    OpenAiChatCompletionsHttpRequestPolicy.MESSAGE_PARSE_BODY_FAILED);
        }
        JsonNode model = root.get(OpenAiChatCompletionsBodyPolicy.FIELD_MODEL);
        if (model == null || !model.isTextual() || model.asText().isBlank()) {
            return ValidationResult.rejected(
                    OpenAiChatCompletionsHttpRequestPolicy.STATUS_BAD_REQUEST,
                    OpenAiChatCompletionsHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST,
                    OpenAiChatCompletionsHttpRequestPolicy.MESSAGE_MODEL_REQUIRED);
        }
        return ValidationResult.acceptedResult();
    }

    private static JsonNode parseBody(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            log.debug("Failed to parse Chat Completions request body while validating HTTP request");
            return null;
        }
    }

    public record ValidationResult(
            boolean accepted,
            int status,
            String code,
            String message
    ) {
        public static ValidationResult acceptedResult() {
            return new ValidationResult(true, 0, "", "");
        }

        public static ValidationResult rejected(int status, String code, String message) {
            return new ValidationResult(false, status, code, message);
        }
    }
}
