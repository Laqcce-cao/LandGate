package com.landgate.types.gateway;

import com.landgate.types.enums.Platform;

/**
 * Stable client-entry access facts for gateway route handling.
 *
 * <p>This policy owns only request-platform/group access decisions and
 * client-visible error facts. It must not resolve groups, select accounts,
 * parse request JSON, or write servlet responses.</p>
 */
public final class GatewayClientAccessPolicy {

    public static final int FORBIDDEN_STATUS = 403;
    public static final String ERROR_CODE_PERMISSION = ErrorResponsePolicy.ERROR_CODE_PERMISSION;
    public static final String CLAUDE_CODE_ONLY_MESSAGES_ROUTE_MESSAGE =
            "This group is restricted to Claude Code clients (/v1/messages only)";

    private GatewayClientAccessPolicy() {
    }

    public static boolean rejectsClaudeCodeOnlyNonAnthropicRoute(Boolean claudeCodeOnly,
                                                                 Platform requestPlatform) {
        return Boolean.TRUE.equals(claudeCodeOnly) && requestPlatform != Platform.ANTHROPIC;
    }

    public static String groupProtocolNotAllowedMessage(String requestFormat) {
        return "This group does not allow protocol: " + GatewayProtocolFormat.normalizeId(requestFormat);
    }
}
