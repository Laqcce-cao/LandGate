package com.landgate.types.gateway;

/**
 * Stable OpenAI Responses JSON field/value facts.
 *
 * <p>This type owns JSON names, item type values, status values, and small
 * pure helpers only. It must not parse or mutate JSON, read streams, write
 * HTTP responses, choose routes, translate protocols, or calculate billing.</p>
 */
public final class OpenAiResponsesJsonPolicy {

    public static final String FIELD_ACTION = "action";
    public static final String FIELD_ARGUMENTS = "arguments";
    public static final String FIELD_CACHED_TOKENS = "cached_tokens";
    public static final String FIELD_CALL_ID = "call_id";
    public static final String FIELD_CODE = "code";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_CONTENT_INDEX = "content_index";
    public static final String FIELD_DELTA = "delta";
    public static final String FIELD_ERROR = "error";
    public static final String FIELD_ENCRYPTED_CONTENT = "encrypted_content";
    public static final String FIELD_FILE_DATA = "file_data";
    public static final String FIELD_FILE_ID = "file_id";
    public static final String FIELD_FILE_URL = "file_url";
    public static final String FIELD_FILENAME = "filename";
    public static final String FIELD_ID = "id";
    public static final String FIELD_INCOMPLETE_DETAILS = "incomplete_details";
    public static final String FIELD_INPUT_TOKENS = "input_tokens";
    public static final String FIELD_INPUT_TOKENS_DETAILS = "input_tokens_details";
    public static final String FIELD_ITEM = "item";
    public static final String FIELD_MESSAGE = "message";
    public static final String FIELD_MODEL = "model";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_OBJECT = "object";
    public static final String FIELD_CREATED_AT = "created_at";
    public static final String FIELD_OUTPUT = "output";
    public static final String FIELD_OUTPUT_INDEX = "output_index";
    public static final String FIELD_OUTPUT_TOKENS = "output_tokens";
    public static final String FIELD_OUTPUT_TOKENS_DETAILS = "output_tokens_details";
    public static final String FIELD_PART = "part";
    public static final String FIELD_PROPERTIES = "properties";
    public static final String FIELD_QUERY = "query";
    public static final String FIELD_REASON = "reason";
    public static final String FIELD_REFUSAL = "refusal";
    public static final String FIELD_RESPONSE = "response";
    public static final String FIELD_RESPONSE_ID = "response_id";
    public static final String FIELD_ROLE = "role";
    public static final String FIELD_SEQUENCE_NUMBER = "sequence_number";
    public static final String FIELD_STATUS = "status";
    public static final String FIELD_SUMMARY = "summary";
    public static final String FIELD_SUMMARY_INDEX = "summary_index";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_ITEM_ID = "item_id";
    public static final String FIELD_TOTAL_TOKENS = "total_tokens";
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_USAGE = "usage";

    public static final String OBJECT_RESPONSE = "response";

    public static final String TYPE_INPUT_FILE = "input_file";
    public static final String TYPE_INPUT_AUDIO = "input_audio";
    public static final String TYPE_INPUT_IMAGE = "input_image";
    public static final String TYPE_INPUT_TEXT = "input_text";
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_FUNCTION_CALL = "function_call";
    public static final String TYPE_TOOL_CALL = "tool_call";
    public static final String TYPE_CUSTOM_TOOL_CALL = "custom_tool_call";
    public static final String TYPE_CUSTOM_TOOL_CALL_OUTPUT = "custom_tool_call_output";
    public static final String TYPE_FUNCTION_CALL_OUTPUT = "function_call_output";
    public static final String TYPE_ITEM_REFERENCE = "item_reference";
    public static final String TYPE_LOCAL_SHELL_CALL = "local_shell_call";
    public static final String TYPE_MCP_TOOL_CALL = "mcp_tool_call";
    public static final String TYPE_MCP_TOOL_CALL_OUTPUT = "mcp_tool_call_output";
    public static final String TYPE_MESSAGE = "message";
    public static final String TYPE_OBJECT = "object";
    public static final String TYPE_OUTPUT_TEXT = "output_text";
    public static final String TYPE_REASONING = "reasoning";
    public static final String TYPE_REFUSAL = "refusal";
    public static final String TYPE_REASONING_TEXT = "reasoning_text";
    public static final String TYPE_SUMMARY_TEXT = "summary_text";
    public static final String TYPE_TOOL_SEARCH_CALL = "tool_search_call";
    public static final String TYPE_TOOL_SEARCH_OUTPUT = "tool_search_output";
    public static final String TYPE_WEB_SEARCH_CALL = "web_search_call";

    public static final String ROLE_ASSISTANT = "assistant";

    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_INCOMPLETE = "incomplete";
    public static final String STATUS_IN_PROGRESS = "in_progress";

    public static final String ERROR_CODE_INVALID_ENCRYPTED_CONTENT = "invalid_encrypted_content";

    public static final String DEFAULT_INCOMPLETE_REASON = "max_output_tokens";
    public static final String DEFAULT_MODEL = "unknown";
    public static final String EMPTY_JSON_OBJECT = "{}";
    public static final String RESPONSE_ID_PREFIX = "resp_";
    public static final String MESSAGE_ID_PREFIX = "msg_";
    public static final String REASONING_ID_PREFIX = "rsn_";
    public static final String ITEM_ID_PREFIX = "item_";
    public static final int RESPONSE_ID_RANDOM_LENGTH = 24;

    private OpenAiResponsesJsonPolicy() {
    }

    public static boolean isTextOutputPart(String type) {
        return TYPE_OUTPUT_TEXT.equals(type) || TYPE_REFUSAL.equals(type);
    }

    public static boolean isIncompleteStatus(String status) {
        return STATUS_INCOMPLETE.equals(status);
    }

    public static boolean isFailedStatus(String status) {
        return STATUS_FAILED.equals(status);
    }
}
