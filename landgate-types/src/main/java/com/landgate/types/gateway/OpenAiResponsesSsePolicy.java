package com.landgate.types.gateway;

import java.util.Set;

/**
 * Stable OpenAI Responses SSE protocol facts.
 *
 * <p>This type owns event names and small pure helpers only. It must not parse
 * JSON, mutate response bodies, select routes, translate protocols, or write
 * HTTP responses.</p>
 */
public final class OpenAiResponsesSsePolicy {

    public static final String DATA_PREFIX = "data:";
    public static final String DATA_LINE_PREFIX = "data: ";
    public static final String EVENT_LINE_PREFIX = "event: ";
    public static final String FRAME_SEPARATOR_LINE = "";
    public static final String DONE_SENTINEL = "[DONE]";

    public static final String EVENT_RESPONSE_CREATED = "response.created";
    public static final String EVENT_RESPONSE_COMPLETED = "response.completed";
    public static final String EVENT_RESPONSE_DONE = "response.done";
    public static final String EVENT_RESPONSE_FAILED = "response.failed";
    public static final String EVENT_RESPONSE_INCOMPLETE = "response.incomplete";
    public static final String EVENT_RESPONSE_CANCELLED = "response.cancelled";
    public static final String EVENT_RESPONSE_CANCELED = "response.canceled";
    public static final String EVENT_OUTPUT_ITEM_ADDED = "response.output_item.added";
    public static final String EVENT_OUTPUT_ITEM_DONE = "response.output_item.done";
    public static final String EVENT_OUTPUT_TEXT_DELTA = "response.output_text.delta";
    public static final String EVENT_OUTPUT_TEXT_DONE = "response.output_text.done";
    public static final String EVENT_CONTENT_PART_DONE = "response.content_part.done";
    public static final String EVENT_REFUSAL_DELTA = "response.refusal.delta";
    public static final String EVENT_REFUSAL_DONE = "response.refusal.done";
    public static final String EVENT_FUNCTION_CALL_ARGUMENTS_DELTA = "response.function_call_arguments.delta";
    public static final String EVENT_FUNCTION_CALL_ARGUMENTS_DONE = "response.function_call_arguments.done";
    public static final String EVENT_REASONING_SUMMARY_TEXT_DELTA = "response.reasoning_summary_text.delta";
    public static final String EVENT_REASONING_SUMMARY_TEXT_DONE = "response.reasoning_summary_text.done";
    public static final String EVENT_REASONING_TEXT_DELTA = "response.reasoning_text.delta";
    public static final String EVENT_REASONING_TEXT_DONE = "response.reasoning_text.done";

    private static final Set<String> TERMINAL_EVENTS = Set.of(
            EVENT_RESPONSE_COMPLETED,
            EVENT_RESPONSE_DONE,
            EVENT_RESPONSE_FAILED,
            EVENT_RESPONSE_INCOMPLETE,
            EVENT_RESPONSE_CANCELLED,
            EVENT_RESPONSE_CANCELED);

    private OpenAiResponsesSsePolicy() {
    }

    public static Set<String> terminalEvents() {
        return TERMINAL_EVENTS;
    }

    public static boolean isTerminalEvent(String eventType) {
        return eventType != null && TERMINAL_EVENTS.contains(eventType.trim());
    }

    public static boolean isDoneSentinel(String payload) {
        return payload != null && DONE_SENTINEL.equals(payload.trim());
    }

    /**
     * Extracts the payload from an SSE {@code data:} line.
     *
     * <p>Matches Sub2API's OpenAI SSE parser: both {@code data: xxx} and
     * {@code data:xxx} are valid, and leading spaces/tabs after the colon are
     * ignored.</p>
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
}
