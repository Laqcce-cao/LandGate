package com.landgate.types.gateway;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Stable Anthropic Messages request body field/value facts.
 *
 * <p>This type owns field names, role/type values, and small pure predicates
 * only. It must not parse or mutate request bodies, choose routes, perform
 * auth, translate protocols, or calculate billing.</p>
 */
public final class AnthropicMessagesBodyPolicy {

    public static final String FIELD_CACHE_CONTROL = "cache_control";
    public static final String FIELD_CACHE_CREATION = "cache_creation";
    public static final String FIELD_CACHE_CREATION_INPUT_TOKENS = "cache_creation_input_tokens";
    public static final String FIELD_CACHED_TOKENS = "cached_tokens";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_CONTENT_BLOCK = "content_block";
    public static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_DISABLE_PARALLEL_TOOL_USE = "disable_parallel_tool_use";
    public static final String FIELD_DELTA = "delta";
    public static final String FIELD_DATA = "data";
    public static final String FIELD_ID = "id";
    public static final String FIELD_INPUT = "input";
    public static final String FIELD_INPUT_SCHEMA = "input_schema";
    public static final String FIELD_INPUT_TOKENS = "input_tokens";
    public static final String FIELD_CACHE_READ_INPUT_TOKENS = "cache_read_input_tokens";
    public static final String FIELD_MESSAGE = "message";
    public static final String FIELD_MESSAGES = "messages";
    public static final String FIELD_MAX_TOKENS = "max_tokens";
    public static final String FIELD_METADATA = "metadata";
    public static final String FIELD_MODEL = "model";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_FILENAME = "filename";
    public static final String FIELD_FILE_ID = "file_id";
    public static final String FIELD_MEDIA_TYPE = "media_type";
    public static final String FIELD_OUTPUT_TOKENS = "output_tokens";
    public static final String FIELD_PARTIAL_JSON = "partial_json";
    public static final String FIELD_ROLE = "role";
    public static final String FIELD_STOP_SEQUENCES = "stop_sequences";
    public static final String FIELD_STREAM = "stream";
    public static final String FIELD_STOP_REASON = "stop_reason";
    public static final String FIELD_STOP_SEQUENCE = "stop_sequence";
    public static final String FIELD_SOURCE = "source";
    public static final String FIELD_SYSTEM = "system";
    public static final String FIELD_TEMPERATURE = "temperature";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_TITLE = "title";
    public static final String FIELD_TOOL_CHOICE = "tool_choice";
    public static final String FIELD_TOOL_USE_ID = "tool_use_id";
    public static final String FIELD_TOOLS = "tools";
    public static final String FIELD_TTL = "ttl";
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_USAGE = "usage";
    public static final String FIELD_USER_LOCATION = "user_location";
    public static final String FIELD_USER_ID = "user_id";
    public static final String FIELD_URL = "url";
    public static final String FIELD_EPHEMERAL_5M_INPUT_TOKENS = "ephemeral_5m_input_tokens";
    public static final String FIELD_EPHEMERAL_1H_INPUT_TOKENS = "ephemeral_1h_input_tokens";

    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_DEVELOPER = "developer";
    public static final String ROLE_USER = "user";

    public static final String TYPE_AUTO = "auto";
    public static final String TYPE_ANY = "any";
    public static final String TYPE_BASE64 = "base64";
    public static final String TYPE_DOCUMENT = "document";
    public static final String TYPE_FILE = "file";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_URL = "url";
    public static final String TYPE_REDACTED_THINKING = "redacted_thinking";
    public static final String TYPE_INPUT_JSON_DELTA = "input_json_delta";
    public static final String TYPE_MESSAGE = "message";
    public static final String TYPE_SERVER_TOOL_USE = "server_tool_use";
    public static final String TYPE_SIGNATURE_DELTA = AnthropicThinkingPolicy.TYPE_SIGNATURE_DELTA;
    public static final String TYPE_THINKING = "thinking";
    public static final String TYPE_THINKING_DELTA = AnthropicThinkingPolicy.TYPE_THINKING_DELTA;
    public static final String TYPE_TEXT_DELTA = "text_delta";
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_TOOL_RESULT = "tool_result";
    public static final String TYPE_TOOL_CHOICE_NONE = "none";
    public static final String TYPE_TOOL_CHOICE_TOOL = "tool";
    public static final String TYPE_TOOL_USE = "tool_use";
    public static final String TYPE_WEB_SEARCH_TOOL_RESULT = "web_search_tool_result";

    public static final String CACHE_CONTROL_TYPE_EPHEMERAL = "ephemeral";
    public static final String BILLING_HEADER_PREFIX = "x-anthropic-billing-header: ";
    public static final String STOP_REASON_END_TURN = "end_turn";
    public static final String STOP_REASON_MAX_TOKENS = "max_tokens";
    public static final String STOP_REASON_MODEL_CONTEXT_WINDOW_EXCEEDED = "model_context_window_exceeded";
    public static final String STOP_REASON_TOOL_USE = "tool_use";
    public static final String MESSAGE_ID_PREFIX = "msg_";
    public static final String TOOL_NAME_READ = "Read";
    public static final String TOOL_NAME_WEB_SEARCH = GatewayWebSearchToolPolicy.TOOL_NAME_WEB_SEARCH;
    public static final String WEB_SEARCH_SERVER_TOOL_ID_PREFIX = "srvtoolu_";
    public static final int DEFAULT_MAX_TOKENS = 8192;
    public static final int MESSAGE_ID_RANDOM_LENGTH = 24;

    private AnthropicMessagesBodyPolicy() {
    }

    public static boolean hasCacheControl(JsonNode node) {
        return node != null && node.isObject() && node.has(FIELD_CACHE_CONTROL);
    }

    public static boolean isThinkingType(String type) {
        return TYPE_THINKING.equals(type) || TYPE_REDACTED_THINKING.equals(type);
    }
}
