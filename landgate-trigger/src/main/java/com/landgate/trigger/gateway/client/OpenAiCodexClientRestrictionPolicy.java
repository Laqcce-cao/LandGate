package com.landgate.trigger.gateway.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.gateway.OpenAiAccountAuthPolicy;
import com.landgate.types.gateway.OpenAiCodexProfile;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Sub2API-compatible OpenAI OAuth Codex client restriction policy.
 *
 * <p>This policy only reads account route flags and request headers. It must
 * not choose accounts, build upstream auth, normalize request bodies, write
 * responses, or perform protocol translation.</p>
 */
public final class OpenAiCodexClientRestrictionPolicy {

    public static final int ERROR_STATUS = 403;
    public static final String ERROR_CODE = "forbidden_error";
    public static final String ERROR_MESSAGE = "This account only allows Codex official clients";

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiCodexClientRestrictionPolicy() {
    }

    public static DetectionResult detect(AccountEntity account, UpstreamRoute route, HttpServletRequest request) {
        boolean enabled = isRestrictionEnabled(account, route);
        if (!enabled) {
            return new DetectionResult(false, true, "disabled");
        }

        String userAgent = header(request, OpenAiCodexProfile.HEADER_USER_AGENT);
        String originator = header(request, OpenAiCodexProfile.HEADER_ORIGINATOR);
        boolean matched = OpenAiCodexProfile.isCodexOfficialClient(userAgent, originator);
        return new DetectionResult(true, matched, matched ? "matched" : "not_official_codex_client");
    }

    public static boolean isRestrictionEnabled(AccountEntity account, UpstreamRoute route) {
        if (account == null || route == null) {
            return false;
        }
        if (!OpenAiAccountAuthPolicy.isOpenAiOAuth(account.getPlatform(), account.getType())) {
            return false;
        }
        if (route.endpointKind() != EndpointKind.OPENAI_CODEX_RESPONSES) {
            return false;
        }
        return accountExtraBoolean(account, OpenAiCodexProfile.ACCOUNT_EXTRA_CODEX_CLI_ONLY);
    }

    private static boolean accountExtraBoolean(AccountEntity account, String field) {
        String extra = account.getExtra();
        if (extra == null || extra.isBlank() || "{}".equals(extra.trim())) {
            return false;
        }
        try {
            JsonNode root = JSON.readTree(extra);
            JsonNode value = root.get(field);
            return value != null && value.isBoolean() && value.asBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String header(HttpServletRequest request, String name) {
        if (request == null || name == null || name.isBlank()) {
            return "";
        }
        String value = request.getHeader(name);
        return value == null ? "" : value;
    }

    public record DetectionResult(boolean enabled, boolean matched, String reason) {
        public boolean rejected() {
            return enabled && !matched;
        }
    }
}
