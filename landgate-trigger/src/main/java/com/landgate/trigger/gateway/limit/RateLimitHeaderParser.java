package com.landgate.trigger.gateway.limit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.types.enums.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 上游 API Rate Limit 响应头解析器 —— 按平台归一化为 {@link RateLimitSnapshot}。
 * <p>
 * 支持 Anthropic（{@code anthropic-ratelimit-*} 前缀）和
 * OpenAI（{@code x-ratelimit-*} 前缀）。
 * 解析失败或缺少头信息时返回空快照，不中断网关主流程。
 */
@Slf4j
@Component
public class RateLimitHeaderParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- Anthropic 头前缀 ----
    private static final String ANTHROPIC_TOKEN_LIMIT     = "anthropic-ratelimit-tokens-limit";
    private static final String ANTHROPIC_TOKEN_REMAINING = "anthropic-ratelimit-tokens-remaining";
    private static final String ANTHROPIC_TOKEN_RESET     = "anthropic-ratelimit-tokens-reset";
    private static final String ANTHROPIC_REQ_LIMIT       = "anthropic-ratelimit-requests-limit";
    private static final String ANTHROPIC_REQ_REMAINING   = "anthropic-ratelimit-requests-remaining";
    private static final String ANTHROPIC_REQ_RESET       = "anthropic-ratelimit-requests-reset";

    // ---- OpenAI 头前缀 ----
    private static final String OPENAI_TOKEN_LIMIT     = "x-ratelimit-limit-tokens";
    private static final String OPENAI_TOKEN_REMAINING = "x-ratelimit-remaining-tokens";
    private static final String OPENAI_TOKEN_RESET     = "x-ratelimit-reset-tokens";
    private static final String OPENAI_REQ_LIMIT       = "x-ratelimit-limit-requests";
    private static final String OPENAI_REQ_REMAINING   = "x-ratelimit-remaining-requests";
    private static final String OPENAI_REQ_RESET       = "x-ratelimit-reset-requests";

    // ---- OpenAI OAuth Codex 内部端点限额头 ----
    private static final String CODEX_ACTIVE_LIMIT = "x-codex-active-limit";
    private static final String CODEX_CREDITS_UNLIMITED = "x-codex-credits-unlimited";
    private static final String CODEX_PRIMARY_USED_PERCENT = "x-codex-primary-used-percent";
    private static final String CODEX_PRIMARY_RESET_AFTER_SECONDS = "x-codex-primary-reset-after-seconds";
    private static final String CODEX_PRIMARY_RESET_AT = "x-codex-primary-reset-at";
    private static final String CODEX_PRIMARY_WINDOW_MINUTES = "x-codex-primary-window-minutes";
    private static final String CODEX_SECONDARY_USED_PERCENT = "x-codex-secondary-used-percent";
    private static final String CODEX_SECONDARY_RESET_AFTER_SECONDS = "x-codex-secondary-reset-after-seconds";
    private static final String CODEX_SECONDARY_RESET_AT = "x-codex-secondary-reset-at";
    private static final String CODEX_SECONDARY_WINDOW_MINUTES = "x-codex-secondary-window-minutes";
    private static final String CODEX_PRIMARY_OVER_SECONDARY_LIMIT_PERCENT = "x-codex-primary-over-secondary-limit-percent";

    /**
     * 解析上游响应头，提取 Rate Limit 信息。
     *
     * @param headers  上游 HTTP 响应头
     * @param platform 账号平台
     * @return 归一化的 RateLimitSnapshot；无数据时 hasData()==false
     */
    public RateLimitSnapshot parse(HttpHeaders headers, Platform platform) {
        if (headers == null || platform == null) {
            return empty();
        }
        try {
            return switch (platform) {
                case ANTHROPIC, ANTIGRAVITY -> parseAnthropic(headers);
                case OPENAI -> parseOpenAI(headers);
                default -> empty();
            };
        } catch (Exception e) {
            log.debug("Failed to parse rate limit headers: platform={}, error={}", platform, e.getMessage());
            return empty();
        }
    }

    // ---- Anthropic 解析 ----

    private RateLimitSnapshot parseAnthropic(HttpHeaders headers) {
        Map<String, Bucket> buckets = new LinkedHashMap<>();

        parseBucket(headers, ANTHROPIC_TOKEN_LIMIT, ANTHROPIC_TOKEN_REMAINING,
                ANTHROPIC_TOKEN_RESET, true, "tokens").ifPresent(b -> buckets.put("tokens", b));
        parseBucket(headers, ANTHROPIC_REQ_LIMIT, ANTHROPIC_REQ_REMAINING,
                ANTHROPIC_REQ_RESET, true, "requests").ifPresent(b -> buckets.put("requests", b));

        return buildSnapshot(buckets);
    }

    // ---- OpenAI 解析 ----

    private RateLimitSnapshot parseOpenAI(HttpHeaders headers) {
        RateLimitSnapshot codexSnapshot = parseCodex(headers);
        if (codexSnapshot.hasData()) {
            return codexSnapshot;
        }

        Map<String, Bucket> buckets = new LinkedHashMap<>();

        parseBucket(headers, OPENAI_TOKEN_LIMIT, OPENAI_TOKEN_REMAINING,
                OPENAI_TOKEN_RESET, false, "tokens").ifPresent(b -> buckets.put("tokens", b));
        parseBucket(headers, OPENAI_REQ_LIMIT, OPENAI_REQ_REMAINING,
                OPENAI_REQ_RESET, false, "requests").ifPresent(b -> buckets.put("requests", b));

        return buildSnapshot(buckets);
    }

    /** 解析 ChatGPT Codex 内部端点返回的 5h/7d 限额窗口。 */
    private RateLimitSnapshot parseCodex(HttpHeaders headers) {
        CodexWindow primary = parseCodexWindow(headers, "primary", CODEX_PRIMARY_USED_PERCENT,
                CODEX_PRIMARY_RESET_AFTER_SECONDS, CODEX_PRIMARY_RESET_AT, CODEX_PRIMARY_WINDOW_MINUTES);
        CodexWindow secondary = parseCodexWindow(headers, "secondary", CODEX_SECONDARY_USED_PERCENT,
                CODEX_SECONDARY_RESET_AFTER_SECONDS, CODEX_SECONDARY_RESET_AT, CODEX_SECONDARY_WINDOW_MINUTES);
        Double overSecondaryPercent = parseDouble(headers, CODEX_PRIMARY_OVER_SECONDARY_LIMIT_PERCENT).orElse(null);
        String activeLimit = headers.firstValue(CODEX_ACTIVE_LIMIT).orElse(null);
        Boolean creditsUnlimited = parseBoolean(headers, CODEX_CREDITS_UNLIMITED).orElse(null);

        boolean hasData = primary.hasData() || secondary.hasData() || overSecondaryPercent != null
                || activeLimit != null || creditsUnlimited != null;
        if (!hasData) {
            return empty();
        }

        List<CodexWindow> windows = normalizeCodexWindows(primary, secondary);
        Instant now = Instant.now();
        Instant farthestReset = windows.stream()
                .map(CodexWindow::resetAt)
                .filter(r -> r != null)
                .max(Comparator.naturalOrder())
                .orElse(null);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("source", "openai_oauth_codex");
        root.put("captured_at", now.toString());
        if (activeLimit != null) {
            root.put("active_limit", activeLimit);
        } else {
            root.putNull("active_limit");
        }
        if (creditsUnlimited != null) {
            root.put("credits_unlimited", creditsUnlimited);
        } else {
            root.putNull("credits_unlimited");
        }
        if (overSecondaryPercent != null) {
            root.put("primary_over_secondary_limit_percent", overSecondaryPercent);
        } else {
            root.putNull("primary_over_secondary_limit_percent");
        }

        ArrayNode windowNodes = MAPPER.createArrayNode();
        for (CodexWindow window : windows) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("label", window.label());
            node.put("scope", window.scope());
            putNullableInt(node, "window_minutes", window.windowMinutes());
            putNullableDouble(node, "used_percent", window.usedPercent());
            putNullableDouble(node, "remaining_percent", window.remainingPercent());
            putNullableInt(node, "reset_after_seconds", window.resetAfterSeconds());
            if (window.resetAt() != null) {
                node.put("reset_at", window.resetAt().toString());
            } else {
                node.putNull("reset_at");
            }
            windowNodes.add(node);
        }
        root.set("windows", windowNodes);

        try {
            return new RateLimitSnapshot(now, farthestReset, MAPPER.writeValueAsString(root));
        } catch (Exception e) {
            log.debug("Failed to serialize Codex rate limit JSON: {}", e.getMessage());
            return empty();
        }
    }

    // ---- 通用 bucket 解析 ----

    private record Bucket(long limit, long remaining, Instant reset) {}

    private record CodexWindow(String scope, String label, Double usedPercent, Integer resetAfterSeconds,
                               Instant resetAt, Integer windowMinutes) {
        boolean hasData() {
            return usedPercent != null || resetAfterSeconds != null || resetAt != null || windowMinutes != null;
        }

        Double remainingPercent() {
            if (usedPercent == null) {
                return null;
            }
            return Math.max(0D, 100D - usedPercent);
        }

        CodexWindow withLabel(String label) {
            return new CodexWindow(scope, label, usedPercent, resetAfterSeconds, resetAt, windowMinutes);
        }
    }

    private CodexWindow parseCodexWindow(HttpHeaders headers, String scope, String usedHeader,
                                         String resetAfterHeader, String resetAtHeader, String windowHeader) {
        Double usedPercent = parseDouble(headers, usedHeader).orElse(null);
        Integer resetAfterSeconds = parseInt(headers, resetAfterHeader).orElse(null);
        Instant resetAt = parseEpochSecond(headers, resetAtHeader).orElse(null);
        Integer windowMinutes = parseInt(headers, windowHeader).orElse(null);
        return new CodexWindow(scope, scope, usedPercent, resetAfterSeconds, resetAt, windowMinutes);
    }

    private List<CodexWindow> normalizeCodexWindows(CodexWindow primary, CodexWindow secondary) {
        List<CodexWindow> windows = new ArrayList<>(2);
        if (primary.windowMinutes() != null && secondary.windowMinutes() != null) {
            if (primary.windowMinutes() <= secondary.windowMinutes()) {
                windows.add(primary.withLabel("5h"));
                windows.add(secondary.withLabel("7d"));
            } else {
                windows.add(secondary.withLabel("5h"));
                windows.add(primary.withLabel("7d"));
            }
        } else if (primary.windowMinutes() != null) {
            windows.add(primary.withLabel(primary.windowMinutes() <= 360 ? "5h" : "7d"));
            if (secondary.hasData()) {
                windows.add(secondary.withLabel(primary.windowMinutes() <= 360 ? "7d" : "5h"));
            }
        } else if (secondary.windowMinutes() != null) {
            windows.add(secondary.withLabel(secondary.windowMinutes() <= 360 ? "5h" : "7d"));
            if (primary.hasData()) {
                windows.add(primary.withLabel(secondary.windowMinutes() <= 360 ? "7d" : "5h"));
            }
        } else {
            // 兼容没有 window_minutes 的旧头：沿用 sub2api 的 legacy 假设 primary=7d, secondary=5h。
            if (secondary.hasData()) {
                windows.add(secondary.withLabel("5h"));
            }
            if (primary.hasData()) {
                windows.add(primary.withLabel("7d"));
            }
        }
        return windows;
    }

    private Optional<Integer> parseInt(HttpHeaders headers, String name) {
        try {
            return headers.firstValue(name).map(Integer::parseInt);
        } catch (Exception e) {
            log.debug("Failed to parse int header '{}': {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Double> parseDouble(HttpHeaders headers, String name) {
        try {
            return headers.firstValue(name).map(Double::parseDouble);
        } catch (Exception e) {
            log.debug("Failed to parse double header '{}': {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Boolean> parseBoolean(HttpHeaders headers, String name) {
        return headers.firstValue(name).map(Boolean::parseBoolean);
    }

    private Optional<Instant> parseEpochSecond(HttpHeaders headers, String name) {
        try {
            return headers.firstValue(name).map(value -> Instant.ofEpochSecond(Long.parseLong(value)));
        } catch (Exception e) {
            log.debug("Failed to parse epoch second header '{}': {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    private void putNullableInt(ObjectNode node, String name, Integer value) {
        if (value != null) {
            node.put(name, value);
        } else {
            node.putNull(name);
        }
    }

    private void putNullableDouble(ObjectNode node, String name, Double value) {
        if (value != null) {
            node.put(name, value);
        } else {
            node.putNull(name);
        }
    }

    private Optional<Bucket> parseBucket(HttpHeaders headers,
                                          String limitHeader, String remainingHeader, String resetHeader,
                                          boolean resetIsIso8601, String bucketName) {
        try {
            String limitStr = headers.firstValue(limitHeader).orElse(null);
            String remainingStr = headers.firstValue(remainingHeader).orElse(null);
            String resetStr = headers.firstValue(resetHeader).orElse(null);

            // 至少需要 limit 和 remaining 才认为有有效数据
            if (limitStr == null || remainingStr == null) {
                return Optional.empty();
            }

            long limit = Long.parseLong(limitStr);
            long remaining = Long.parseLong(remainingStr);

            Instant reset = null;
            if (resetStr != null) {
                reset = resetIsIso8601
                        ? Instant.parse(resetStr)
                        : Instant.ofEpochSecond(Long.parseLong(resetStr));
            }

            return Optional.of(new Bucket(limit, remaining, reset));
        } catch (Exception e) {
            log.debug("Failed to parse rate limit bucket '{}': {}", bucketName, e.getMessage());
            return Optional.empty();
        }
    }

    // ---- 构建快照 ----

    private RateLimitSnapshot buildSnapshot(Map<String, Bucket> buckets) {
        if (buckets.isEmpty()) {
            return empty();
        }

        ObjectNode root = MAPPER.createObjectNode();
        Instant now = Instant.now();
        Instant farthestReset = null;

        for (var entry : buckets.entrySet()) {
            Bucket b = entry.getValue();
            ObjectNode node = MAPPER.createObjectNode();
            node.put("limit", b.limit);
            node.put("remaining", b.remaining);
            if (b.reset != null) {
                node.put("reset", b.reset.toString());
                if (farthestReset == null || b.reset.isAfter(farthestReset)) {
                    farthestReset = b.reset;
                }
            }
            root.set(entry.getKey(), node);
        }

        try {
            String json = MAPPER.writeValueAsString(root);
            return new RateLimitSnapshot(now, farthestReset, json);
        } catch (Exception e) {
            log.debug("Failed to serialize rate limit JSON: {}", e.getMessage());
            return empty();
        }
    }

    private RateLimitSnapshot empty() {
        return new RateLimitSnapshot(null, null, null);
    }
}
