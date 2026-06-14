package com.landgate.types.gateway;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sub2API-compatible previous_response_id classification for OpenAI Responses.
 *
 * <p>This policy owns only stable protocol facts and diagnostics. It must not
 * parse JSON, mutate requests, choose routes, or write HTTP responses.</p>
 */
public final class OpenAiPreviousResponseIdPolicy {

    public static final String KIND_EMPTY = "empty";
    public static final String KIND_RESPONSE_ID = "response_id";
    public static final String KIND_MESSAGE_ID = "message_id";
    public static final String KIND_UNKNOWN = "unknown";

    public static final String MESSAGE_PREVIOUS_RESPONSE_ID_MUST_BE_RESPONSE =
            "previous_response_id must be a response.id (resp_*), not a message id";
    public static final String MESSAGE_PREVIOUS_RESPONSE_ID_WEBSOCKET_ONLY =
            "previous_response_id is only supported on Responses WebSocket v2";

    private static final Pattern RESPONSE_ID_PATTERN = Pattern.compile("^resp_[A-Za-z0-9_-]{1,256}$");
    private static final Pattern MESSAGE_ID_PATTERN = Pattern.compile("^(msg|message|item|chatcmpl)_[A-Za-z0-9_-]{1,256}$");

    private OpenAiPreviousResponseIdPolicy() {
    }

    public static String classify(String id) {
        String trimmed = trim(id);
        if (trimmed.isBlank()) {
            return KIND_EMPTY;
        }
        if (RESPONSE_ID_PATTERN.matcher(trimmed).matches()) {
            return KIND_RESPONSE_ID;
        }
        if (MESSAGE_ID_PATTERN.matcher(trimmed.toLowerCase(Locale.ROOT)).matches()) {
            return KIND_MESSAGE_ID;
        }
        return KIND_UNKNOWN;
    }

    public static boolean likelyMessageId(String id) {
        return KIND_MESSAGE_ID.equals(classify(id));
    }

    public static boolean shouldRejectHttpResponsesPreviousResponseId(String id) {
        return !KIND_EMPTY.equals(classify(id));
    }

    public static String httpResponsesPreviousResponseIdMessage(String id) {
        if (likelyMessageId(id)) {
            return MESSAGE_PREVIOUS_RESPONSE_ID_MUST_BE_RESPONSE;
        }
        return MESSAGE_PREVIOUS_RESPONSE_ID_WEBSOCKET_ONLY;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
