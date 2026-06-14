package com.landgate.types.gateway;

/**
 * Stable Anthropic Messages SSE protocol facts.
 *
 * <p>This type owns event names and small SSE helpers only. It must not parse
 * JSON, mutate response bodies, choose routes, translate protocols, or perform
 * billing.</p>
 */
public final class AnthropicMessagesSsePolicy {

    public static final String DATA_PREFIX = "data:";
    public static final String DATA_LINE_PREFIX = "data: ";
    public static final String EVENT_LINE_PREFIX = "event: ";
    public static final String FRAME_SEPARATOR_LINE = "";
    public static final String DONE_SENTINEL = "[DONE]";

    public static final String EVENT_CONTENT_BLOCK_DELTA = "content_block_delta";
    public static final String EVENT_CONTENT_BLOCK_START = "content_block_start";
    public static final String EVENT_CONTENT_BLOCK_STOP = "content_block_stop";
    public static final String EVENT_ERROR = "error";
    public static final String EVENT_MESSAGE_DELTA = "message_delta";
    public static final String EVENT_MESSAGE_START = "message_start";
    public static final String EVENT_MESSAGE_STOP = "message_stop";
    public static final String DEFAULT_ERROR_TYPE = "api_error";
    public static final String DEFAULT_ERROR_MESSAGE = "Anthropic stream error";

    private AnthropicMessagesSsePolicy() {
    }

    /**
     * Extracts the payload from an Anthropic SSE {@code data:} line.
     *
     * <p>Matches the tolerant OpenAI SSE parser used elsewhere in the gateway:
     * both {@code data: xxx} and {@code data:xxx} are valid, and leading spaces
     * or tabs after the colon are ignored.</p>
     */
    public static String extractDataPayload(String line) {
        if (line == null || !line.startsWith(DATA_PREFIX)) {
            return null;
        }
        int start = DATA_PREFIX.length();
        while (start < line.length()) {
            char ch = line.charAt(start);
            if (ch != ' ' && ch != '\t') {
                break;
            }
            start++;
        }
        return line.substring(start);
    }

    public static boolean isDoneSentinel(String payload) {
        return payload != null && DONE_SENTINEL.equals(payload.trim());
    }

    public static boolean isEventLine(String line, String eventName) {
        if (line == null || eventName == null || !line.startsWith(EVENT_LINE_PREFIX)) {
            return false;
        }
        return eventName.equals(line.substring(EVENT_LINE_PREFIX.length()).trim());
    }

    public static boolean isMessageStopEventLine(String line) {
        return isEventLine(line, EVENT_MESSAGE_STOP);
    }

    public static boolean isMessageStopType(String type) {
        return EVENT_MESSAGE_STOP.equals(type);
    }
}
