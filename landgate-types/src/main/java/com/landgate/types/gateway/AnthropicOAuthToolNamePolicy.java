package com.landgate.types.gateway;

import java.util.List;
import java.util.Map;

/**
 * Claude OAuth tool-name mimicry facts.
 *
 * <p>This type owns stable Sub2API/Parrot-compatible values only. Request body
 * mutation and response byte restoration live in the gateway layer.</p>
 */
public final class AnthropicOAuthToolNamePolicy {

    public static final int DYNAMIC_TOOL_MAP_THRESHOLD = 5;
    public static final int DYNAMIC_TOOL_NAME_HEAD_LENGTH = 3;
    public static final String TOOL_TYPE_FUNCTION = "function";
    public static final String TOOL_TYPE_CUSTOM = "custom";

    public static final Map<String, String> STATIC_REWRITES = Map.of(
            "sessions_", "cc_sess_",
            "session_", "cc_ses_"
    );

    public static final List<String> FAKE_NAME_PREFIXES = List.of(
            "analyze_", "compute_", "fetch_", "generate_", "lookup_", "modify_",
            "process_", "query_", "render_", "resolve_", "sync_", "update_",
            "validate_", "convert_", "extract_", "manage_", "monitor_", "parse_",
            "review_", "search_", "transform_", "handle_", "invoke_", "notify_"
    );

    private AnthropicOAuthToolNamePolicy() {
    }

    public static boolean shouldMimicToolName(String toolType) {
        return toolType == null
                || toolType.isBlank()
                || TOOL_TYPE_FUNCTION.equals(toolType)
                || TOOL_TYPE_CUSTOM.equals(toolType);
    }
}
