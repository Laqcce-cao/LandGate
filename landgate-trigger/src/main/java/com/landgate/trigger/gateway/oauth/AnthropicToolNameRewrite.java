package com.landgate.trigger.gateway.oauth;

import com.landgate.types.gateway.AnthropicOAuthToolNamePolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-request Claude OAuth tool-name rewrite mapping.
 *
 * <p>Forward mappings are applied before forwarding to Anthropic OAuth.
 * Reverse mappings are applied to upstream response chunks/bodies before the
 * client sees them.</p>
 */
public record AnthropicToolNameRewrite(
        Map<String, String> forward,
        List<Replacement> reverseOrdered
) {

    public record Replacement(String from, String to) {
    }

    public AnthropicToolNameRewrite {
        forward = forward == null ? Map.of() : Map.copyOf(forward);
        reverseOrdered = reverseOrdered == null ? List.of() : List.copyOf(reverseOrdered);
    }

    public static AnthropicToolNameRewrite fromToolNames(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return empty();
        }

        Map<String, String> dynamic = dynamicToolMap(toolNames);
        Map<String, String> forward = new LinkedHashMap<>();
        Map<String, String> reverse = new LinkedHashMap<>();
        for (String name : toolNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String fake = sanitizeToolName(name, dynamic);
            if (fake.equals(name)) {
                continue;
            }
            forward.put(name, fake);
            reverse.put(fake, name);
        }
        if (forward.isEmpty()) {
            return empty();
        }

        List<Replacement> reverseOrdered = new ArrayList<>();
        reverse.forEach((fake, real) -> reverseOrdered.add(new Replacement(fake, real)));
        reverseOrdered.sort(Comparator.comparingInt((Replacement r) -> r.from().length()).reversed());
        return new AnthropicToolNameRewrite(forward, reverseOrdered);
    }

    public static AnthropicToolNameRewrite empty() {
        return new AnthropicToolNameRewrite(Map.of(), List.of());
    }

    public boolean hasRewrite() {
        return !forward.isEmpty();
    }

    public String fakeName(String realName) {
        return realName == null ? null : forward.get(realName);
    }

    public String restore(String data) {
        if (data == null || data.isEmpty()) {
            return data;
        }
        String out = data;
        for (Replacement replacement : reverseOrdered) {
            out = replace(out, replacement.from(), replacement.to());
        }
        for (Map.Entry<String, String> entry : AnthropicOAuthToolNamePolicy.STATIC_REWRITES.entrySet()) {
            out = replace(out, entry.getValue(), entry.getKey());
        }
        return out;
    }

    private static String sanitizeToolName(String name, Map<String, String> dynamic) {
        if (dynamic != null && dynamic.containsKey(name)) {
            return dynamic.get(name);
        }
        for (Map.Entry<String, String> entry : AnthropicOAuthToolNamePolicy.STATIC_REWRITES.entrySet()) {
            if (name.startsWith(entry.getKey())) {
                return entry.getValue() + name.substring(entry.getKey().length());
            }
        }
        return name;
    }

    private static Map<String, String> dynamicToolMap(List<String> toolNames) {
        if (toolNames.size() <= AnthropicOAuthToolNamePolicy.DYNAMIC_TOOL_MAP_THRESHOLD) {
            return null;
        }

        List<String> prefixes = new ArrayList<>(AnthropicOAuthToolNamePolicy.FAKE_NAME_PREFIXES);
        Collections.shuffle(prefixes, new java.util.Random(fnv64Seed(toolNames)));

        Map<String, String> mapping = new LinkedHashMap<>();
        for (int i = 0; i < toolNames.size(); i++) {
            String name = toolNames.get(i);
            int headLength = Math.min(AnthropicOAuthToolNamePolicy.DYNAMIC_TOOL_NAME_HEAD_LENGTH, name.length());
            String fake = prefixes.get(i % prefixes.size()) + name.substring(0, headLength) + String.format("%02d", i);
            mapping.put(name, fake);
        }
        return mapping;
    }

    private static long fnv64Seed(List<String> toolNames) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < toolNames.size(); i++) {
            if (i > 0) {
                hash ^= 0;
                hash *= 0x100000001b3L;
            }
            byte[] bytes = toolNames.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (byte b : bytes) {
                hash ^= (b & 0xff);
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    private static String replace(String data, String from, String to) {
        if (from == null || from.isEmpty() || from.equals(to) || !data.contains(from)) {
            return data;
        }
        return data.replace(from, to);
    }
}
