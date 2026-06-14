package com.landgate.types.gateway;

import java.util.Locale;

/**
 * Sub2API-compatible OpenAI transient 400 processing error classifier.
 *
 * <p>This type owns only pure error-message classification facts. It must not
 * choose accounts, mutate request bodies, write responses, or run failover.</p>
 */
public final class OpenAiTransientProcessingErrorPolicy {

    public static final int STATUS_BAD_REQUEST = 400;
    public static final String PHRASE_PROCESSING_ERROR =
            "an error occurred while processing your request";
    public static final String PHRASE_RETRY_REQUEST = "you can retry your request";
    public static final String PHRASE_HELP_CENTER = "help.openai.com";
    public static final String PHRASE_REQUEST_ID = "request id";

    private OpenAiTransientProcessingErrorPolicy() {
    }

    public static boolean isTransientProcessingError(int statusCode, String upstreamMessage, String upstreamBody) {
        if (statusCode != STATUS_BAD_REQUEST) {
            return false;
        }
        return matches(upstreamMessage)
                || matches(ErrorResponsePolicy.extractUpstreamErrorMessage(upstreamBody))
                || matches(upstreamBody);
    }

    public static boolean matches(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        if (normalized.contains(PHRASE_PROCESSING_ERROR)) {
            return true;
        }
        return normalized.contains(PHRASE_RETRY_REQUEST)
                && normalized.contains(PHRASE_HELP_CENTER)
                && normalized.contains(PHRASE_REQUEST_ID);
    }
}
