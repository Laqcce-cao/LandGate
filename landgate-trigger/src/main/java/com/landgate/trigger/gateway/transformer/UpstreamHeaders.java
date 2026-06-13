package com.landgate.trigger.gateway.transformer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Small header builder with HTTP Header.Set/Header.Del semantics.
 */
final class UpstreamHeaders {

    private final LinkedHashMap<String, HeaderValue> values = new LinkedHashMap<>();

    void set(String name, String value) {
        if (isBlank(name) || isBlank(value)) return;
        values.put(normalize(name), new HeaderValue(name, value));
    }

    void setIfAbsent(String name, String value) {
        if (isBlank(name) || isBlank(value) || values.containsKey(normalize(name))) return;
        set(name, value);
    }

    void remove(String name) {
        if (isBlank(name)) return;
        values.remove(normalize(name));
    }

    String get(String name) {
        HeaderValue value = values.get(normalize(name));
        return value == null ? "" : value.value();
    }

    void copyAllowed(Map<String, String> source, Set<String> allowedLowercaseNames) {
        if (source == null || allowedLowercaseNames == null || allowedLowercaseNames.isEmpty()) {
            return;
        }
        source.forEach((name, value) -> {
            String normalized = normalize(name);
            if (isBlank(normalized) || isBlank(value) || !allowedLowercaseNames.contains(normalized)) {
                return;
            }
            set(name, value);
        });
    }

    String[] toArray() {
        ArrayList<String> out = new ArrayList<>(values.size() * 2);
        values.values().forEach(value -> {
            out.add(value.name());
            out.add(value.value());
        });
        return out.toArray(new String[0]);
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record HeaderValue(String name, String value) {
    }
}
