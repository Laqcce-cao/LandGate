package com.landgate.types.gateway;

import java.util.Locale;

/**
 * Stable previous-response continuation error matching for OpenAI Responses compatibility.
 *
 * <p>This type owns only string matching facts and pure predicates. It must not
 * mutate session state, choose routes, write responses, execute retries, or
 * access caches.</p>
 */
public final class OpenAiCompatPreviousResponsePolicy {

    public static final String ERROR_PREVIOUS_RESPONSE_NOT_FOUND = "previous_response_not_found";
    public static final String PHRASE_PREVIOUS_RESPONSE = "previous response";
    public static final String PHRASE_NOT_FOUND = "not found";
    public static final String PHRASE_UNSUPPORTED_PARAMETER = "unsupported parameter";
    public static final String PHRASE_RESPONSES_WEBSOCKET_ONLY = "only supported on responses websocket";
    public static final String PHRASE_NOT_SUPPORTED = "not supported";

    private OpenAiCompatPreviousResponsePolicy() {
    }

    public static boolean isNotFound(int statusCode, String errorBody) {
        if (statusCode != 400 && statusCode != 404) return false;
        String lower = normalize(errorBody);
        return lower.contains(ERROR_PREVIOUS_RESPONSE_NOT_FOUND)
                || (lower.contains(PHRASE_PREVIOUS_RESPONSE) && lower.contains(PHRASE_NOT_FOUND))
                || (lower.contains(PHRASE_UNSUPPORTED_PARAMETER)
                && lower.contains(OpenAiResponsesBodyPolicy.FIELD_PREVIOUS_RESPONSE_ID));
    }

    public static boolean isUnsupported(int statusCode, String errorBody) {
        if (statusCode != 400) return false;
        String lower = normalize(errorBody);
        if (!lower.contains(OpenAiResponsesBodyPolicy.FIELD_PREVIOUS_RESPONSE_ID)) return false;
        return lower.contains(PHRASE_UNSUPPORTED_PARAMETER)
                || lower.contains(PHRASE_RESPONSES_WEBSOCKET_ONLY)
                || lower.contains(PHRASE_NOT_SUPPORTED);
    }

    public static String normalize(String errorBody) {
        return errorBody == null ? "" : errorBody.trim().toLowerCase(Locale.ROOT);
    }
}
