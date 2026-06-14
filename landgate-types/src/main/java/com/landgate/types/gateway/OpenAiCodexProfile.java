package com.landgate.types.gateway;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stable OpenAI Codex client/header profile constants.
 *
 * <p>This type only exposes protocol/profile facts. Request body normalization,
 * auth execution, route selection, and response handling remain outside types.</p>
 */
public final class OpenAiCodexProfile {

    public static final String CLI_VERSION = "0.125.0";
    public static final String CLI_USER_AGENT = "codex_cli_rs/" + CLI_VERSION;
    public static final String DEFAULT_MODEL = "gpt-5.4";
    public static final String DEFAULT_INSTRUCTIONS = "You are a helpful coding assistant.";
    public static final String ORIGINATOR_CODEX_CLI_RS = "codex_cli_rs";
    public static final String OPENAI_BETA_RESPONSES_EXPERIMENTAL = "responses=experimental";
    public static final String CREDENTIAL_CHATGPT_ACCOUNT_ID = "chatgpt_account_id";
    public static final String CREDENTIAL_USER_AGENT = "user_agent";
    public static final String ACCOUNT_EXTRA_CODEX_CLI_ONLY = "codex_cli_only";
    public static final String FIELD_PROMPT_CACHE_KEY = OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_KEY;

    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String ACCEPT_EVENT_STREAM = "text/event-stream";
    public static final String ACCEPT_JSON = "application/json";
    public static final String AUTH_BEARER_PREFIX = "Bearer ";

    public static final String HEADER_ACCEPT = "Accept";
    public static final String HEADER_ACCEPT_LANGUAGE = "Accept-Language";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_CHATGPT_ACCOUNT_ID = "chatgpt-account-id";
    public static final String HEADER_CONTENT_TYPE = "Content-Type";
    public static final String HEADER_CONVERSATION_ID = "conversation_id";
    public static final String HEADER_OPENAI_BETA = "OpenAI-Beta";
    public static final String HEADER_ORIGINATOR = "Originator";
    public static final String HEADER_SESSION_ID = "session_id";
    public static final String HEADER_USER_AGENT = "User-Agent";
    public static final String HEADER_VERSION = "Version";
    public static final String HEADER_X_CODEX_TURN_METADATA = "x-codex-turn-metadata";
    public static final String HEADER_X_CODEX_TURN_STATE = "x-codex-turn-state";

    public static final String HEADER_CODEX_ACTIVE_LIMIT = "x-codex-active-limit";
    public static final String HEADER_CODEX_CREDITS_UNLIMITED = "x-codex-credits-unlimited";
    public static final String HEADER_CODEX_PRIMARY_USED_PERCENT = "x-codex-primary-used-percent";
    public static final String HEADER_CODEX_PRIMARY_RESET_AFTER_SECONDS = "x-codex-primary-reset-after-seconds";
    public static final String HEADER_CODEX_PRIMARY_RESET_AT = "x-codex-primary-reset-at";
    public static final String HEADER_CODEX_PRIMARY_WINDOW_MINUTES = "x-codex-primary-window-minutes";
    public static final String HEADER_CODEX_SECONDARY_USED_PERCENT = "x-codex-secondary-used-percent";
    public static final String HEADER_CODEX_SECONDARY_RESET_AFTER_SECONDS = "x-codex-secondary-reset-after-seconds";
    public static final String HEADER_CODEX_SECONDARY_RESET_AT = "x-codex-secondary-reset-at";
    public static final String HEADER_CODEX_SECONDARY_WINDOW_MINUTES = "x-codex-secondary-window-minutes";
    public static final String HEADER_CODEX_PRIMARY_OVER_SECONDARY_LIMIT_PERCENT =
            "x-codex-primary-over-secondary-limit-percent";

    public static final String RATE_LIMIT_SOURCE = "openai_oauth_codex";
    public static final String RATE_LIMIT_SCOPE_PRIMARY = "primary";
    public static final String RATE_LIMIT_SCOPE_SECONDARY = "secondary";
    public static final String RATE_LIMIT_LABEL_SHORT_WINDOW = "5h";
    public static final String RATE_LIMIT_LABEL_LONG_WINDOW = "7d";
    public static final int RATE_LIMIT_SHORT_WINDOW_MAX_MINUTES = 360;

    public static final List<String> CODEX_CLI_USER_AGENT_PREFIXES = List.of(
            "codex_vscode/",
            "codex_cli_rs/");

    public static final List<String> CODEX_OFFICIAL_CLIENT_USER_AGENT_PREFIXES = List.of(
            "codex_cli_rs/",
            "codex_vscode/",
            "codex_app/",
            "codex_chatgpt_desktop/",
            "codex_atlas/",
            "codex_exec/",
            "codex_sdk_ts/",
            "codex ");

    public static final List<String> CODEX_OFFICIAL_CLIENT_ORIGINATOR_PREFIXES = List.of(
            "codex_",
            "codex ");

    private static final Set<String> UNSUPPORTED_REQUEST_FIELDS = Set.of(
            OpenAiResponsesBodyPolicy.FIELD_MAX_OUTPUT_TOKENS,
            OpenAiResponsesBodyPolicy.FIELD_MAX_COMPLETION_TOKENS,
            OpenAiResponsesBodyPolicy.FIELD_TEMPERATURE,
            OpenAiResponsesBodyPolicy.FIELD_TOP_P,
            OpenAiResponsesBodyPolicy.FIELD_FREQUENCY_PENALTY,
            OpenAiResponsesBodyPolicy.FIELD_PRESENCE_PENALTY,
            OpenAiResponsesBodyPolicy.FIELD_USER,
            OpenAiResponsesBodyPolicy.FIELD_METADATA,
            OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_KEY,
            OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_RETENTION,
            OpenAiResponsesBodyPolicy.FIELD_SAFETY_IDENTIFIER,
            OpenAiResponsesBodyPolicy.FIELD_STREAM_OPTIONS);

    private static final Map<String, String> MODEL_ALIASES = Map.ofEntries(
            Map.entry("gpt-5.5", "gpt-5.5"),
            Map.entry("gpt-5.4", "gpt-5.4"),
            Map.entry("gpt-5.4-mini", "gpt-5.4-mini"),
            Map.entry("gpt-5.4-none", "gpt-5.4"),
            Map.entry("gpt-5.4-low", "gpt-5.4"),
            Map.entry("gpt-5.4-medium", "gpt-5.4"),
            Map.entry("gpt-5.4-high", "gpt-5.4"),
            Map.entry("gpt-5.4-xhigh", "gpt-5.4"),
            Map.entry("gpt-5.4-chat-latest", "gpt-5.4"),
            Map.entry("gpt-5.3", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-none", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-low", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-medium", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-high", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-xhigh", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-codex", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-codex-spark", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex-spark-low", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex-spark-medium", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex-spark-high", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex-spark-xhigh", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex-low", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-codex-medium", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-codex-high", "gpt-5.3-codex"),
            Map.entry("gpt-5.3-codex-xhigh", "gpt-5.3-codex"),
            Map.entry("gpt-5.2", "gpt-5.2"),
            Map.entry("gpt-5.2-none", "gpt-5.2"),
            Map.entry("gpt-5.2-low", "gpt-5.2"),
            Map.entry("gpt-5.2-medium", "gpt-5.2"),
            Map.entry("gpt-5.2-high", "gpt-5.2"),
            Map.entry("gpt-5.2-xhigh", "gpt-5.2"),
            Map.entry("gpt-5", "gpt-5.4"),
            Map.entry("gpt-5-mini", "gpt-5.4"),
            Map.entry("gpt-5-nano", "gpt-5.4"),
            Map.entry("gpt-5.1", "gpt-5.4"),
            Map.entry("gpt-5.1-codex", "gpt-5.3-codex"),
            Map.entry("gpt-5.1-codex-max", "gpt-5.3-codex"),
            Map.entry("gpt-5.1-codex-mini", "gpt-5.3-codex"),
            Map.entry("gpt-5.2-codex", "gpt-5.2"),
            Map.entry("codex-mini-latest", "gpt-5.3-codex"),
            Map.entry("gpt-5-codex", "gpt-5.3-codex"));

    private static final List<Map.Entry<String, String>> VERSION_MODEL_PREFIXES = List.of(
            Map.entry("gpt-5.3-codex-spark", "gpt-5.3-codex-spark"),
            Map.entry("gpt-5.3-codex", "gpt-5.3-codex"),
            Map.entry("gpt-5.4-mini", "gpt-5.4-mini"),
            Map.entry("gpt-5.4-nano", "gpt-5.4-nano"),
            Map.entry("gpt-5.5", "gpt-5.5"),
            Map.entry("gpt-5.4", "gpt-5.4"),
            Map.entry("gpt-5.2", "gpt-5.2"));

    private OpenAiCodexProfile() {
    }

    public static Set<String> unsupportedRequestFields() {
        return UNSUPPORTED_REQUEST_FIELDS;
    }

    public static String normalizeModel(String model) {
        String trimmed = model == null ? "" : model.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_MODEL;
        }
        String known = normalizeKnownModel(trimmed);
        return known.isBlank() ? trimmed : known;
    }

    public static boolean supportsTextVerbosity(String model) {
        String value = model == null ? "" : model.trim();
        if (!value.startsWith("gpt-")) {
            return true;
        }

        String version = value.substring("gpt-".length());
        int majorEnd = leadingDigitsEnd(version, 0);
        if (majorEnd == 0) {
            return true;
        }
        int major = parseIntOrDefault(version.substring(0, majorEnd), 0);
        if (major > 5) {
            return true;
        }
        if (major < 5) {
            return false;
        }
        if (majorEnd >= version.length() || version.charAt(majorEnd) != '.') {
            return true;
        }

        int minorStart = majorEnd + 1;
        int minorEnd = leadingDigitsEnd(version, minorStart);
        int minor = minorEnd > minorStart
                ? parseIntOrDefault(version.substring(minorStart, minorEnd), 0)
                : 0;
        return minor >= 3;
    }

    public static boolean isCodexCliUserAgent(String userAgent) {
        String normalized = normalizeClientHeader(userAgent);
        if (normalized.isBlank()) {
            return false;
        }
        return matchesAnyClientPrefix(normalized, CODEX_CLI_USER_AGENT_PREFIXES);
    }

    public static boolean isCodexOfficialClientUserAgent(String userAgent) {
        String normalized = normalizeClientHeader(userAgent);
        if (normalized.isBlank()) {
            return false;
        }
        return matchesAnyClientPrefix(normalized, CODEX_OFFICIAL_CLIENT_USER_AGENT_PREFIXES);
    }

    public static boolean isCodexOfficialClientOriginator(String originator) {
        String normalized = normalizeClientHeader(originator);
        if (normalized.isBlank()) {
            return false;
        }
        return matchesAnyClientPrefix(normalized, CODEX_OFFICIAL_CLIENT_ORIGINATOR_PREFIXES);
    }

    public static boolean isCodexOfficialClient(String userAgent, String originator) {
        return isCodexOfficialClientUserAgent(userAgent) || isCodexOfficialClientOriginator(originator);
    }

    public static String headerKey(String headerName) {
        return normalizeClientHeader(headerName);
    }

    public static String bearerToken(String accessToken) {
        return AUTH_BEARER_PREFIX + accessToken;
    }

    private static String normalizeKnownModel(String model) {
        String trimmed = model == null ? "" : model.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (isOpenAIImageGenerationModel(trimmed)) {
            return trimmed;
        }

        String modelId = lastOpenAIModelSegment(trimmed);
        String canonical = canonicalizeOpenAIModelAliasSpelling(modelId);
        if (!canonical.isBlank()) {
            modelId = canonical;
        }

        String mapped = normalizeKnownOpenAICodexModel(modelId);
        if (!mapped.isBlank()) {
            return mapped;
        }

        String key = codexModelLookupKey(modelId);
        if (key.isBlank()) {
            return "";
        }
        mapped = MODEL_ALIASES.getOrDefault(key, "");
        if (!mapped.isBlank()) {
            return mapped;
        }
        for (Map.Entry<String, String> prefix : VERSION_MODEL_PREFIXES) {
            if (key.equals(prefix.getKey())) {
                return prefix.getValue();
            }
            String prefixWithDash = prefix.getKey() + "-";
            if (key.startsWith(prefixWithDash)
                    && isKnownCodexModelSuffix(key.substring(prefixWithDash.length()))) {
                return prefix.getValue();
            }
        }
        return "";
    }

    private static String normalizeKnownOpenAICodexModel(String model) {
        String normalized = canonicalizeOpenAIModelAliasSpelling(model);
        if (normalized.isBlank()) {
            return "";
        }

        String mapped = MODEL_ALIASES.getOrDefault(codexModelLookupKey(normalized), "");
        if (!mapped.isBlank()) {
            return mapped;
        }
        if (normalized.endsWith("-openai-compact")) {
            mapped = MODEL_ALIASES.getOrDefault(
                    codexModelLookupKey(normalized.substring(0, normalized.length() - "-openai-compact".length())),
                    "");
            if (!mapped.isBlank()) {
                return mapped;
            }
        }

        if (normalized.contains("gpt-5.5")) return "gpt-5.5";
        if (normalized.contains("gpt-5.4-mini")) return "gpt-5.4-mini";
        if (normalized.contains("gpt-5.4-nano")) return "gpt-5.4-nano";
        if (normalized.contains("gpt-5.4")) return "gpt-5.4";
        if (normalized.contains("gpt-5.2")) return "gpt-5.2";
        if (normalized.contains("gpt-5.3-codex-spark")) return "gpt-5.3-codex-spark";
        if (normalized.contains("gpt-5.3-codex")) return "gpt-5.3-codex";
        if (normalized.contains("gpt-5.3")) return "gpt-5.3-codex";
        if (normalized.contains("codex")) return "gpt-5.3-codex";
        if (normalized.contains("gpt-5")) return DEFAULT_MODEL;
        return "";
    }

    private static String canonicalizeOpenAIModelAliasSpelling(String model) {
        String normalized = lastOpenAIModelSegment(model).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }

        normalized = normalized.replace('_', '-');
        normalized = String.join("-", normalized.trim().split("\\s+"));
        while (normalized.contains("--")) {
            normalized = normalized.replace("--", "-");
        }
        if (normalized.startsWith("gpt5")) {
            normalized = "gpt-5" + normalized.substring("gpt5".length());
        }
        if (!normalized.startsWith("gpt-") && !normalized.contains("codex")) {
            return "";
        }

        normalized = normalized.replace("gpt-5.4mini", "gpt-5.4-mini");
        normalized = normalized.replace("gpt-5.4nano", "gpt-5.4-nano");
        normalized = normalized.replace("gpt-5.3-codexspark", "gpt-5.3-codex-spark");
        normalized = normalized.replace("gpt-5.3codexspark", "gpt-5.3-codex-spark");
        normalized = normalized.replace("gpt-5.3codex", "gpt-5.3-codex");
        return normalized;
    }

    private static String lastOpenAIModelSegment(String model) {
        String value = model == null ? "" : model.trim();
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1).trim() : value;
    }

    private static String codexModelLookupKey(String modelId) {
        String value = modelId == null ? "" : modelId.trim();
        if (value.isBlank()) {
            return "";
        }
        value = lastOpenAIModelSegment(value);
        return String.join("-", value.toLowerCase(Locale.ROOT).trim().split("\\s+"));
    }

    private static boolean isKnownCodexModelSuffix(String suffix) {
        return switch (suffix) {
            case "none", "minimal", "low", "medium", "high", "xhigh" -> true;
            default -> isCodexDateSuffix(suffix);
        };
    }

    private static boolean isCodexDateSuffix(String suffix) {
        if (suffix == null) return false;
        String[] parts = suffix.split("-");
        if (parts.length != 3 || parts[0].length() != 4 || parts[1].length() != 2 || parts[2].length() != 2) {
            return false;
        }
        for (String part : parts) {
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isOpenAIImageGenerationModel(String model) {
        return model != null && model.trim().toLowerCase(Locale.ROOT).startsWith("gpt-image-");
    }

    private static int leadingDigitsEnd(String value, int start) {
        int i = start;
        while (i < value.length() && Character.isDigit(value.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int parseIntOrDefault(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean matchesAnyClientPrefix(String value, List<String> prefixes) {
        for (String prefix : prefixes) {
            String normalizedPrefix = normalizeClientHeader(prefix);
            if (normalizedPrefix.isBlank()) {
                continue;
            }
            if (value.startsWith(normalizedPrefix) || value.contains(normalizedPrefix)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeClientHeader(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
