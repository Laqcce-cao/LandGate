package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.types.enums.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.util.LinkedHashMap;
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
        Map<String, Bucket> buckets = new LinkedHashMap<>();

        parseBucket(headers, OPENAI_TOKEN_LIMIT, OPENAI_TOKEN_REMAINING,
                OPENAI_TOKEN_RESET, false, "tokens").ifPresent(b -> buckets.put("tokens", b));
        parseBucket(headers, OPENAI_REQ_LIMIT, OPENAI_REQ_REMAINING,
                OPENAI_REQ_RESET, false, "requests").ifPresent(b -> buckets.put("requests", b));

        return buildSnapshot(buckets);
    }

    // ---- 通用 bucket 解析 ----

    private record Bucket(long limit, long remaining, Instant reset) {}

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
