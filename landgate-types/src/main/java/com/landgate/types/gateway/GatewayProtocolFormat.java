package com.landgate.types.gateway;

import java.util.Locale;

/**
 * Stable gateway protocol format identifiers.
 */
public enum GatewayProtocolFormat {

    MESSAGES("messages"),
    RESPONSES("responses"),
    CHAT_COMPLETIONS("chat_completions");

    public static final String WILDCARD = "*";
    public static final String NON_CORE_GEMINI_ID = "gemini";

    private final String id;

    GatewayProtocolFormat(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean is(String value) {
        return id.equals(value);
    }

    public static String normalizeId(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        while (value.contains("__")) {
            value = value.replace("__", "_");
        }
        return switch (value) {
            case "anthropic", "anthropic_messages", "messages_api" -> MESSAGES.id();
            case "openai", "openai_chat", "chat", "chat_completion", "chat_completions" -> CHAT_COMPLETIONS.id();
            case "openai_responses", "response", "responses_api" -> RESPONSES.id();
            case "google", "google_gemini", "gemini_generate_content" -> NON_CORE_GEMINI_ID;
            default -> value;
        };
    }

    public static boolean isWildcard(String value) {
        return WILDCARD.equals(value);
    }
}
