package com.landgate.types.gateway;

import java.util.Set;

/**
 * Stable OpenAI Responses request body policy facts.
 *
 * <p>This type owns field names and small pure policy helpers only. It must not
 * parse or mutate JSON, build HTTP requests, choose routes, perform auth, or
 * translate protocols.</p>
 */
public final class OpenAiResponsesBodyPolicy {

    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_CITY = "city";
    public static final String FIELD_COUNTRY = "country";
    public static final String FIELD_INSTRUCTIONS = "instructions";
    public static final String FIELD_IMAGE_URL = "image_url";
    public static final String FIELD_INPUT = "input";
    public static final String FIELD_MODEL = "model";
    public static final String FIELD_MAX_COMPLETION_TOKENS = "max_completion_tokens";
    public static final String FIELD_MAX_OUTPUT_TOKENS = "max_output_tokens";
    public static final String FIELD_PREVIOUS_RESPONSE_ID = "previous_response_id";
    public static final String FIELD_PROMPT_CACHE_KEY = "prompt_cache_key";
    public static final String FIELD_PROMPT_CACHE_RETENTION = "prompt_cache_retention";
    public static final String FIELD_SAFETY_IDENTIFIER = "safety_identifier";
    public static final String FIELD_SERVICE_TIER = "service_tier";
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_ROLE = "role";
    public static final String FIELD_CALL_ID = "call_id";
    public static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_FREQUENCY_PENALTY = "frequency_penalty";
    public static final String FIELD_FORMAT = "format";
    public static final String FIELD_REGION = "region";
    public static final String FIELD_INCLUDE = "include";
    public static final String FIELD_METADATA = "metadata";
    public static final String FIELD_PARALLEL_TOOL_CALLS = "parallel_tool_calls";
    public static final String FIELD_PARAMETERS = "parameters";
    public static final String FIELD_PRESENCE_PENALTY = "presence_penalty";
    public static final String FIELD_REASONING = "reasoning";
    public static final String FIELD_EFFORT = "effort";
    public static final String FIELD_SUMMARY = "summary";
    public static final String FIELD_STORE = "store";
    public static final String FIELD_STREAM = "stream";
    public static final String FIELD_STREAM_OPTIONS = "stream_options";
    public static final String FIELD_STRICT = "strict";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_TOOL_CHOICE = "tool_choice";
    public static final String FIELD_TOOLS = "tools";
    public static final String FIELD_TOP_LOGPROBS = "top_logprobs";
    public static final String FIELD_TOP_P = "top_p";
    public static final String FIELD_TEMPERATURE = "temperature";
    public static final String FIELD_TIMEZONE = "timezone";
    public static final String FIELD_USER = "user";
    public static final String FIELD_USER_LOCATION = "user_location";

    public static final String INCLUDE_MESSAGE_OUTPUT_TEXT_LOGPROBS = "message.output_text.logprobs";
    public static final String INCLUDE_REASONING_ENCRYPTED_CONTENT = "reasoning.encrypted_content";

    public static final String DEFAULT_EMPTY_TOOL_OUTPUT = "(empty)";
    public static final String DEFAULT_DOCUMENT_MEDIA_TYPE = "application/pdf";
    public static final String DEFAULT_IMAGE_MEDIA_TYPE = "image/png";
    public static final int MIN_MAX_OUTPUT_TOKENS = 128;

    public static final String REASONING_EFFORT_MINIMAL = "minimal";
    public static final String REASONING_EFFORT_NONE = "none";
    public static final String REASONING_SUMMARY_AUTO = "auto";

    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_DEVELOPER = "developer";
    public static final String ROLE_USER = "user";

    public static final String TEXT_VERBOSITY_MEDIUM = "medium";
    public static final String TOOL_CHOICE_AUTO = "auto";
    public static final String TOOL_CHOICE_FUNCTION = "function";
    public static final String TOOL_CHOICE_NONE = "none";
    public static final String TOOL_CHOICE_REQUIRED = "required";

    public static final String TYPE_FUNCTION_CALL = "function_call";
    public static final String TYPE_FUNCTION_CALL_OUTPUT = "function_call_output";
    public static final String TYPE_INPUT_IMAGE = "input_image";
    public static final String TYPE_INPUT_TEXT = "input_text";
    public static final String TYPE_MESSAGE = "message";

    private static final String DATA_URI_PREFIX = "data:";
    private static final String DATA_URI_BASE64_MARKER = "base64,";

    private static final Set<String> PUBLIC_RESPONSES_UNSUPPORTED_FIELDS = Set.of(
            FIELD_MAX_OUTPUT_TOKENS,
            FIELD_MAX_COMPLETION_TOKENS,
            FIELD_PROMPT_CACHE_RETENTION,
            FIELD_SAFETY_IDENTIFIER);

    private static final Set<String> OPENAI_API_KEY_RESPONSES_COMPAT_UNSUPPORTED_FIELDS = Set.of(
            FIELD_MAX_OUTPUT_TOKENS,
            FIELD_MAX_COMPLETION_TOKENS);

    private static final Set<String> CHAT_ENDPOINT_RESPONSES_SHAPE_UNSUPPORTED_FIELDS = Set.of(
            FIELD_PROMPT_CACHE_RETENTION,
            FIELD_SAFETY_IDENTIFIER,
            FIELD_METADATA,
            FIELD_STREAM_OPTIONS);

    private OpenAiResponsesBodyPolicy() {
    }

    public static Set<String> publicResponsesUnsupportedFields() {
        return PUBLIC_RESPONSES_UNSUPPORTED_FIELDS;
    }

    public static Set<String> openAiApiKeyResponsesCompatUnsupportedFields() {
        return OPENAI_API_KEY_RESPONSES_COMPAT_UNSUPPORTED_FIELDS;
    }

    public static Set<String> chatEndpointResponsesShapeUnsupportedFields() {
        return CHAT_ENDPOINT_RESPONSES_SHAPE_UNSUPPORTED_FIELDS;
    }

    public static String normalizeServiceTier(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase();
        if (value.isBlank()) return "";
        if ("fast".equals(value)) {
            value = "priority";
        }
        return switch (value) {
            case "priority", "flex", "auto", "default", "scale" -> value;
            default -> "";
        };
    }

    public static String normalizeReasoningEffort(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (REASONING_EFFORT_MINIMAL.equals(value)) {
            return REASONING_EFFORT_NONE;
        }
        return value;
    }

    public static boolean isEmptyBase64DataUri(String raw) {
        if (raw == null || !raw.startsWith(DATA_URI_PREFIX)) return false;
        String rest = raw.substring(DATA_URI_PREFIX.length());
        int semicolon = rest.indexOf(';');
        if (semicolon < 0) return false;
        rest = rest.substring(semicolon + 1);
        if (!rest.startsWith(DATA_URI_BASE64_MARKER)) return false;
        return rest.substring(DATA_URI_BASE64_MARKER.length()).trim().isEmpty();
    }

    public static boolean isDataUri(String raw) {
        return raw != null && raw.startsWith(DATA_URI_PREFIX);
    }

    public static String base64DataUri(String mediaType, String data) {
        return DATA_URI_PREFIX + mediaType + ";base64," + data;
    }

    public static boolean isEmptyDataUriPayload(String raw) {
        DataUriParts parts = parseDataUri(raw);
        return parts != null && parts.data().trim().isEmpty();
    }

    public static DataUriParts parseDataUri(String raw) {
        if (!isDataUri(raw)) return null;
        int colonIdx = raw.indexOf(':');
        int commaIdx = raw.indexOf(',');
        if (colonIdx < 0 || commaIdx < 0) return null;
        int semicolonIdx = raw.indexOf(';');
        String mediaType = raw.substring(colonIdx + 1,
                semicolonIdx > colonIdx && semicolonIdx < commaIdx ? semicolonIdx : commaIdx);
        String data = raw.substring(commaIdx + 1);
        return new DataUriParts(mediaType, data);
    }

    public record DataUriParts(String mediaType, String data) {
    }
}
