package com.landgate.trigger.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.group.model.entity.GroupEntity;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 解析客户端/上游协议字段。
 * <p>
 * Group.supportedProtocols 约束客户端入口协议；Account.supportedProtocols
 * 表示该账号实际使用的上游协议。账号配置多项时按第一项作为上游协议。
 */
public final class ProtocolFormatResolver {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ProtocolFormatResolver() {
    }

    public static boolean groupAllowsClientFormat(GroupEntity group, String clientFormat) {
        if (group == null) return true;
        List<String> protocols = parseProtocols(group.getSupportedProtocols());
        if (protocols.isEmpty()) return true;
        String normalized = normalizeFormat(clientFormat);
        return protocols.stream()
                .map(ProtocolFormatResolver::normalizeFormat)
                .anyMatch(value -> "*".equals(value) || value.equals(normalized));
    }

    public static String resolveAccountUpstreamFormat(AccountEntity account, String fallback) {
        List<String> protocols = account == null ? List.of() : parseProtocols(account.getSupportedProtocols());
        for (String protocol : protocols) {
            String normalized = normalizeFormat(protocol);
            if (!normalized.isBlank() && !"*".equals(normalized)) {
                return normalized;
            }
        }
        return normalizeFormat(fallback);
    }

    public static String resolveAccountUpstreamFormat(AccountEntity account, String fallback, Set<String> allowed) {
        String resolved = resolveAccountUpstreamFormat(account, fallback);
        if (allowed == null || allowed.isEmpty() || allowed.contains(resolved)) {
            return resolved;
        }
        return normalizeFormat(fallback);
    }

    public static String normalizeFormat(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        while (value.contains("__")) {
            value = value.replace("__", "_");
        }
        return switch (value) {
            case "anthropic", "anthropic_messages", "messages_api" -> "messages";
            case "openai", "openai_chat", "chat", "chat_completion", "chat_completions" -> "chat_completions";
            case "openai_responses", "response", "responses_api" -> "responses";
            case "google", "google_gemini", "gemini_generate_content" -> "gemini";
            default -> value;
        };
    }

    private static List<String> parseProtocols(String raw) {
        if (raw == null || raw.isBlank() || "[]".equals(raw.trim())) {
            return List.of();
        }
        String trimmed = raw.trim();
        try {
            if (trimmed.startsWith("[")) {
                return JSON.readValue(trimmed, new TypeReference<List<String>>() {});
            }
            return List.of(trimmed);
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
