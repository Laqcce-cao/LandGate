package com.landgate.trigger.gateway.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.AnthropicMessagesHttpRequestPolicy;
import com.landgate.types.gateway.GatewayProtocolFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Validates client-facing HTTP Anthropic Messages requests before route selection.
 *
 * <p>This validator mirrors Sub2API's Messages HTTP entry validation. It only
 * applies when the client URL resolved to the Messages format, so translated
 * internal Anthropic bodies are not revalidated here.</p>
 */
@Slf4j
@Service
public class AnthropicMessagesHttpRequestValidator {

    private static final ObjectMapper JSON = new ObjectMapper();

    public ValidationResult validate(String body, String requestFormat) {
        if (!GatewayProtocolFormat.MESSAGES.is(requestFormat)) {
            return ValidationResult.acceptedResult();
        }
        if (body == null || body.isEmpty()) {
            return ValidationResult.rejected(
                    AnthropicMessagesHttpRequestPolicy.STATUS_BAD_REQUEST,
                    AnthropicMessagesHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST,
                    AnthropicMessagesHttpRequestPolicy.MESSAGE_EMPTY_BODY);
        }
        JsonNode root = parseBody(body);
        if (root == null || !root.isObject()) {
            return ValidationResult.rejected(
                    AnthropicMessagesHttpRequestPolicy.STATUS_BAD_REQUEST,
                    AnthropicMessagesHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST,
                    AnthropicMessagesHttpRequestPolicy.MESSAGE_PARSE_BODY_FAILED);
        }
        JsonNode model = root.get(AnthropicMessagesBodyPolicy.FIELD_MODEL);
        if (model == null || !model.isTextual() || model.asText().isBlank()) {
            return ValidationResult.rejected(
                    AnthropicMessagesHttpRequestPolicy.STATUS_BAD_REQUEST,
                    AnthropicMessagesHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST,
                    AnthropicMessagesHttpRequestPolicy.MESSAGE_MODEL_REQUIRED);
        }
        return ValidationResult.acceptedResult();
    }

    public ValidationResult validateNativeStreamType(String body, String requestFormat, String upstreamFormat) {
        if (!GatewayProtocolFormat.MESSAGES.is(requestFormat) || !GatewayProtocolFormat.MESSAGES.is(upstreamFormat)) {
            return ValidationResult.acceptedResult();
        }
        JsonNode root = parseBody(body);
        if (root == null || !root.isObject()) {
            return ValidationResult.acceptedResult();
        }
        JsonNode stream = root.get(AnthropicMessagesBodyPolicy.FIELD_STREAM);
        if (stream != null && !stream.isBoolean()) {
            return ValidationResult.rejected(
                    AnthropicMessagesHttpRequestPolicy.STATUS_BAD_REQUEST,
                    AnthropicMessagesHttpRequestPolicy.ERROR_CODE_INVALID_REQUEST,
                    AnthropicMessagesHttpRequestPolicy.MESSAGE_INVALID_STREAM_TYPE);
        }
        return ValidationResult.acceptedResult();
    }

    private static JsonNode parseBody(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            log.debug("Failed to parse Anthropic Messages request body while validating HTTP request");
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
