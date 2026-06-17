package com.landgate.types.gateway;

import java.util.Set;

/**
 * Stable OpenAI Chat Completions JSON field/value facts.
 *
 * <p>This type owns field names, object/type values, role values, finish
 * reasons, and small pure predicates only. It must not parse or mutate JSON,
 * build HTTP requests, choose routes, translate protocols, or calculate
 * billing.</p>
 */
public final class OpenAiChatCompletionsBodyPolicy {

    public static final String FIELD_ALLOWED_TOOLS = "allowed_tools";
    public static final String FIELD_ARGUMENTS = "arguments";
    public static final String FIELD_CHOICES = "choices";
    public static final String FIELD_COMPLETION_TOKENS = "completion_tokens";
    public static final String FIELD_COMPLETION_TOKENS_DETAILS = "completion_tokens_details";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_CREATED = "created";
    public static final String FIELD_CUSTOM = "custom";
    public static final String FIELD_DATA = "data";
    public static final String FIELD_DEFINITION = "definition";
    public static final String FIELD_DELTA = "delta";
    public static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_DETAIL = "detail";
    public static final String FIELD_FILE = "file";
    public static final String FIELD_FILE_DATA = "file_data";
    public static final String FIELD_FILE_ID = "file_id";
    public static final String FIELD_FILENAME = "filename";
    public static final String FIELD_FINISH_REASON = "finish_reason";
    public static final String FIELD_FORMAT = "format";
    public static final String FIELD_FUNCTION = "function";
    public static final String FIELD_FUNCTION_CALL = "function_call";
    public static final String FIELD_FUNCTIONS = "functions";
    public static final String FIELD_ID = "id";
    public static final String FIELD_INSTRUCTIONS = OpenAiResponsesBodyPolicy.FIELD_INSTRUCTIONS;
    public static final String FIELD_IMAGE_URL = "image_url";
    public static final String FIELD_INCLUDE_USAGE = "include_usage";
    public static final String FIELD_INDEX = "index";
    public static final String FIELD_INPUT = "input";
    public static final String FIELD_INPUT_AUDIO = "input_audio";
    public static final String FIELD_JSON_SCHEMA = "json_schema";
    public static final String FIELD_LOGPROBS = "logprobs";
    public static final String FIELD_MESSAGE = "message";
    public static final String FIELD_MESSAGES = "messages";
    public static final String FIELD_MODE = "mode";
    public static final String FIELD_MODEL = "model";
    public static final String FIELD_MAX_TOKENS = "max_tokens";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_OBJECT = "object";
    public static final String FIELD_PARAMETERS = "parameters";
    public static final String FIELD_PROMPT_TOKENS = "prompt_tokens";
    public static final String FIELD_PROMPT_TOKENS_DETAILS = "prompt_tokens_details";
    public static final String FIELD_PROPERTIES = "properties";
    public static final String FIELD_REASONING_CONTENT = "reasoning_content";
    public static final String FIELD_REASONING_EFFORT = "reasoning_effort";
    public static final String FIELD_REASONING_TOKENS = "reasoning_tokens";
    public static final String FIELD_REASONING = OpenAiResponsesBodyPolicy.FIELD_REASONING;
    public static final String FIELD_EFFORT = OpenAiResponsesBodyPolicy.FIELD_EFFORT;
    public static final String FIELD_REFUSAL = "refusal";
    public static final String FIELD_REGION = "region";
    public static final String FIELD_RESPONSE_FORMAT = "response_format";
    public static final String FIELD_ROLE = "role";
    public static final String FIELD_SCHEMA = "schema";
    public static final String FIELD_SEARCH_CONTEXT_SIZE = "search_context_size";
    public static final String FIELD_SERVICE_TIER = OpenAiResponsesBodyPolicy.FIELD_SERVICE_TIER;
    public static final String FIELD_STOP = "stop";
    public static final String FIELD_STREAM = "stream";
    public static final String FIELD_STREAM_OPTIONS = "stream_options";
    public static final String FIELD_STRICT = "strict";
    public static final String FIELD_SYNTAX = "syntax";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_THINKING = "thinking";
    public static final String FIELD_TOOL_CALL_ID = "tool_call_id";
    public static final String FIELD_TOOL_CALLS = "tool_calls";
    public static final String FIELD_TOOL_CHOICE = "tool_choice";
    public static final String FIELD_TOOLS = "tools";
    public static final String FIELD_TOTAL_TOKENS = "total_tokens";
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_URL = "url";
    public static final String FIELD_USAGE = "usage";
    public static final String FIELD_USER_LOCATION = "user_location";
    public static final String FIELD_VERBOSITY = "verbosity";
    public static final String FIELD_WEB_SEARCH_OPTIONS = "web_search_options";
    public static final String FIELD_APPROXIMATE = "approximate";
    public static final String FIELD_COUNTRY = "country";
    public static final String FIELD_CITY = "city";
    public static final String FIELD_TIMEZONE = "timezone";

    public static final String OBJECT_CHAT_COMPLETION = "chat.completion";
    public static final String OBJECT_CHAT_COMPLETION_CHUNK = "chat.completion.chunk";

    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_DEVELOPER = "developer";
    public static final String ROLE_FUNCTION = "function";
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_TOOL = "tool";
    public static final String ROLE_USER = "user";

    public static final String TYPE_ALLOWED_TOOLS = "allowed_tools";
    public static final String TYPE_CUSTOM = "custom";
    public static final String TYPE_FILE = "file";
    public static final String TYPE_FUNCTION = "function";
    public static final String TYPE_GRAMMAR = "grammar";
    public static final String TYPE_IMAGE_URL = "image_url";
    public static final String TYPE_INPUT_AUDIO = "input_audio";
    public static final String TYPE_JSON_OBJECT = "json_object";
    public static final String TYPE_JSON_SCHEMA = "json_schema";
    public static final String TYPE_OBJECT = "object";
    public static final String TYPE_REASONING = "reasoning";
    public static final String TYPE_REFUSAL = "refusal";
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_THINKING = "thinking";
    public static final String TYPE_WEB_SEARCH_PREVIEW = "web_search_preview";

    public static final String TOOL_CHOICE_AUTO = "auto";
    public static final String TOOL_CHOICE_NONE = "none";
    public static final String TOOL_CHOICE_REQUIRED = "required";

    public static final String FINISH_REASON_CONTENT_FILTER = "content_filter";
    public static final String FINISH_REASON_LENGTH = "length";
    public static final String FINISH_REASON_STOP = "stop";
    public static final String FINISH_REASON_TOOL_CALLS = "tool_calls";

    public static final String DEFAULT_FUNCTION_ARGUMENTS = "{}";
    public static final String DEFAULT_EMPTY_TOOL_OUTPUT = OpenAiResponsesBodyPolicy.DEFAULT_EMPTY_TOOL_OUTPUT;
    public static final String DEFAULT_MODEL = "unknown";
    public static final String THINKING_OPEN_TAG = "<thinking>";
    public static final String THINKING_CLOSE_TAG = "</thinking>";
    public static final String ID_PREFIX_CHAT_COMPLETION = "chatcmpl-";
    public static final String ID_PREFIX_TOOL_CALL = "call_";
    public static final int ID_RANDOM_LENGTH = 24;

    private static final Set<String> SUPPORTED_TOOL_CHOICE_MODES = Set.of(
            TOOL_CHOICE_AUTO, TOOL_CHOICE_NONE, TOOL_CHOICE_REQUIRED);

    private static final Set<String> SUPPORTED_LEGACY_FUNCTION_CALL_MODES = Set.of(
            TOOL_CHOICE_AUTO, TOOL_CHOICE_NONE);

    private static final Set<String> INCOMPLETE_FINISH_REASONS = Set.of(
            FINISH_REASON_LENGTH, FINISH_REASON_CONTENT_FILTER);

    private static final Set<String> REASONING_EFFORTS = Set.of(
            "low", "medium", "high", "xhigh");

    private static final Set<String> TEXT_VERBOSITY_VALUES = Set.of(
            "low", "medium", "high");

    private static final Set<String> IMAGE_DETAIL_VALUES = Set.of(
            "auto", "low", "high");

    private static final Set<String> SEARCH_CONTEXT_SIZE_VALUES = Set.of(
            "low", "medium", "high");

    private static final Set<String> CUSTOM_TOOL_GRAMMAR_SYNTAX_VALUES = Set.of(
            "lark", "regex");

    private OpenAiChatCompletionsBodyPolicy() {
    }

    public static boolean isSupportedToolChoiceMode(String mode) {
        return SUPPORTED_TOOL_CHOICE_MODES.contains(mode);
    }

    public static boolean isSupportedLegacyFunctionCallMode(String mode) {
        return SUPPORTED_LEGACY_FUNCTION_CALL_MODES.contains(mode);
    }

    public static boolean isIncompleteFinishReason(String finishReason) {
        return INCOMPLETE_FINISH_REASONS.contains(finishReason);
    }

    public static String normalizeReasoningEffort(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase()
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
        if (value.isBlank() || "none".equals(value) || "minimal".equals(value)) {
            return null;
        }
        if ("extrahigh".equals(value)) {
            return "xhigh";
        }
        return REASONING_EFFORTS.contains(value) ? value : null;
    }

    public static String normalizeServiceTier(String raw) {
        return OpenAiResponsesBodyPolicy.normalizeServiceTier(raw);
    }

    public static String normalizeTextVerbosity(String value) {
        return TEXT_VERBOSITY_VALUES.contains(value) ? value : null;
    }

    public static String normalizeImageDetail(String value) {
        return IMAGE_DETAIL_VALUES.contains(value) ? value : null;
    }

    public static String normalizeSearchContextSize(String value) {
        return SEARCH_CONTEXT_SIZE_VALUES.contains(value) ? value : null;
    }

    public static String normalizeCustomToolGrammarSyntax(String value) {
        return CUSTOM_TOOL_GRAMMAR_SYNTAX_VALUES.contains(value) ? value : null;
    }
}
