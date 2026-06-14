package com.landgate.types.gateway;

import java.util.Set;

/**
 * Stable JSON field and value names used by OpenAI Codex body normalization.
 *
 * <p>This type owns constants and small membership checks only. It must not
 * parse JSON, build HTTP requests, perform auth, choose routes, translate
 * protocols, or calculate billing.</p>
 */
public final class OpenAiCodexBodyShapeProfile {

    public static final String FIELD_BACKGROUND = "background";
    public static final String FIELD_CALL_ID = "call_id";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_CONTEXT_MANAGEMENT = "context_management";
    public static final String FIELD_CONVERSATION = "conversation";
    public static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_EFFORT = "effort";
    public static final String FIELD_FUNCTION = "function";
    public static final String FIELD_FUNCTION_CALL = "function_call";
    public static final String FIELD_FUNCTIONS = "functions";
    public static final String FIELD_ID = "id";
    public static final String FIELD_IMAGE_URL = "image_url";
    public static final String FIELD_INCLUDE = "include";
    public static final String FIELD_INPUT = "input";
    public static final String FIELD_INPUT_SCHEMA = "input_schema";
    public static final String FIELD_INSTRUCTIONS = "instructions";
    public static final String FIELD_MAX_TOOL_CALLS = "max_tool_calls";
    public static final String FIELD_METADATA = "metadata";
    public static final String FIELD_MODEL = "model";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_OUTPUT = "output";
    public static final String FIELD_PARALLEL_TOOL_CALLS = "parallel_tool_calls";
    public static final String FIELD_PARAMETERS = "parameters";
    public static final String FIELD_PREVIOUS_RESPONSE_ID = OpenAiResponsesBodyPolicy.FIELD_PREVIOUS_RESPONSE_ID;
    public static final String FIELD_PROMPT = "prompt";
    public static final String FIELD_PROMPT_CACHE_RETENTION = "prompt_cache_retention";
    public static final String FIELD_PROPERTIES = "properties";
    public static final String FIELD_REASONING = "reasoning";
    public static final String FIELD_ROLE = "role";
    public static final String FIELD_SAFETY_IDENTIFIER = "safety_identifier";
    public static final String FIELD_STRICT = "strict";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_TOOL_CALL_ID = "tool_call_id";
    public static final String FIELD_TOOL_CHOICE = "tool_choice";
    public static final String FIELD_TOOL_NAME = "tool_name";
    public static final String FIELD_TOOLS = "tools";
    public static final String FIELD_TRUNCATION = "truncation";
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_USER = "user";

    public static final String ROLE_DEVELOPER = "developer";
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_TOOL = "tool";
    public static final String ROLE_USER = "user";

    public static final String TYPE_CUSTOM_TOOL_CALL = "custom_tool_call";
    public static final String TYPE_CUSTOM_TOOL_CALL_OUTPUT = "custom_tool_call_output";
    public static final String TYPE_FUNCTION = "function";
    public static final String TYPE_FUNCTION_CALL = "function_call";
    public static final String TYPE_FUNCTION_CALL_OUTPUT = "function_call_output";
    public static final String TYPE_INPUT_IMAGE = "input_image";
    public static final String TYPE_ITEM_REFERENCE = "item_reference";
    public static final String TYPE_LOCAL_SHELL_CALL = "local_shell_call";
    public static final String TYPE_MCP_TOOL_CALL = "mcp_tool_call";
    public static final String TYPE_MCP_TOOL_CALL_OUTPUT = "mcp_tool_call_output";
    public static final String TYPE_MESSAGE = "message";
    public static final String TYPE_OBJECT = "object";
    public static final String TYPE_REASONING = "reasoning";
    public static final String TYPE_TOOL_CALL = "tool_call";
    public static final String TYPE_TOOL_SEARCH_CALL = "tool_search_call";
    public static final String TYPE_TOOL_SEARCH_OUTPUT = "tool_search_output";

    public static final String TOOL_CHOICE_AUTO = "auto";
    public static final String TOOL_CHOICE_NONE = "none";
    public static final String TOOL_CHOICE_REQUIRED = "required";

    public static final String REASONING_EFFORT_MINIMAL = OpenAiResponsesBodyPolicy.REASONING_EFFORT_MINIMAL;
    public static final String REASONING_EFFORT_NONE = OpenAiResponsesBodyPolicy.REASONING_EFFORT_NONE;

    public static final String CALL_ID_PREFIX_CALL = OpenAiChatCompletionsBodyPolicy.ID_PREFIX_TOOL_CALL;
    public static final String CALL_ID_PREFIX_FC = "fc";
    public static final String CALL_ID_PREFIX_FC_UNDERSCORE = "fc_";

    private static final Set<String> ALLOWED_TEXT_TOOL_CHOICES = Set.of(
            TOOL_CHOICE_AUTO,
            TOOL_CHOICE_REQUIRED,
            TOOL_CHOICE_NONE);

    private static final Set<String> CODEX_TOOL_CALL_ITEM_TYPES = Set.of(
            TYPE_FUNCTION_CALL,
            TYPE_TOOL_CALL,
            TYPE_LOCAL_SHELL_CALL,
            TYPE_TOOL_SEARCH_CALL,
            TYPE_CUSTOM_TOOL_CALL,
            TYPE_MCP_TOOL_CALL,
            TYPE_FUNCTION_CALL_OUTPUT,
            TYPE_MCP_TOOL_CALL_OUTPUT,
            TYPE_CUSTOM_TOOL_CALL_OUTPUT,
            TYPE_TOOL_SEARCH_OUTPUT);

    private static final Set<String> CODEX_INPUT_ITEM_TYPES_REQUIRING_NAME = Set.of(
            TYPE_FUNCTION_CALL,
            TYPE_CUSTOM_TOOL_CALL,
            TYPE_MCP_TOOL_CALL);

    private OpenAiCodexBodyShapeProfile() {
    }

    public static boolean isAllowedTextToolChoice(String value) {
        return ALLOWED_TEXT_TOOL_CHOICES.contains(value);
    }

    public static boolean isCodexToolCallItemType(String type) {
        return CODEX_TOOL_CALL_ITEM_TYPES.contains(type == null ? "" : type.trim());
    }

    public static boolean codexInputItemRequiresName(String type) {
        return CODEX_INPUT_ITEM_TYPES_REQUIRING_NAME.contains(type == null ? "" : type.trim());
    }

    public static boolean isSystemOrDeveloperRole(String role) {
        return ROLE_SYSTEM.equals(role) || ROLE_DEVELOPER.equals(role);
    }
}
