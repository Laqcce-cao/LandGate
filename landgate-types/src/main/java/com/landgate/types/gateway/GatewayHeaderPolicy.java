package com.landgate.types.gateway;

import java.util.Locale;
import java.util.Map;

/**
 * Stable HTTP header utility policy.
 *
 * <p>This type owns case-insensitive header key/value helpers only. It must not
 * decide authentication, route selection, protocol conversion, or provider
 * specific allowlists.</p>
 */
public final class GatewayHeaderPolicy {

    private GatewayHeaderPolicy() {
    }

    public static String headerKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    public static String value(Map<String, String> headers, String name) {
        if (headers == null || name == null) {
            return "";
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue() == null ? "" : entry.getValue().trim();
            }
        }
        return "";
    }

    public static boolean hasValue(Map<String, String> headers, String name) {
        return !value(headers, name).isBlank();
    }
}
