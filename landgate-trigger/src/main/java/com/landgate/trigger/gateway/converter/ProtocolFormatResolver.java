package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.types.gateway.GatewayProtocolFormat;

import java.util.List;
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
                .anyMatch(value -> GatewayProtocolFormat.isWildcard(value) || value.equals(normalized));
    }

    public static String resolveAccountUpstreamFormat(AccountEntity account, String fallback) {
        List<String> protocols = account == null ? List.of() : parseProtocols(account.getSupportedProtocols());
        for (String protocol : protocols) {
            String normalized = normalizeFormat(protocol);
            if (!normalized.isBlank() && !GatewayProtocolFormat.isWildcard(normalized)) {
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

    public static String requireSingleAccountUpstreamFormat(AccountEntity account, Set<String> allowed) {
        List<String> normalized = (account == null ? List.<String>of() : parseProtocols(account.getSupportedProtocols()))
                .stream()
                .map(ProtocolFormatResolver::normalizeFormat)
                .filter(value -> !value.isBlank() && !GatewayProtocolFormat.isWildcard(value))
                .distinct()
                .toList();
        if (normalized.size() != 1) {
            Long accountId = account != null ? account.getId() : null;
            throw new IllegalArgumentException(
                    "Account supportedProtocols must contain exactly one upstream protocol: account_id=" + accountId);
        }
        String protocol = normalized.get(0);
        if (allowed != null && !allowed.isEmpty() && !allowed.contains(protocol)) {
            Long accountId = account != null ? account.getId() : null;
            throw new IllegalArgumentException(
                    "Account upstream protocol '" + protocol + "' is not allowed for this route: account_id=" + accountId);
        }
        return protocol;
    }

    public static String normalizeFormat(String raw) {
        return GatewayProtocolFormat.normalizeId(raw);
    }

    public static boolean isSameFormat(String left, String right) {
        String normalizedLeft = normalizeFormat(left);
        String normalizedRight = normalizeFormat(right);
        return !normalizedLeft.isBlank() && normalizedLeft.equals(normalizedRight);
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
