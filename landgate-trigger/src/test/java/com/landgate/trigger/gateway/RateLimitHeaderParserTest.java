package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.trigger.gateway.limit.RateLimitHeaderParser;
import com.landgate.trigger.gateway.limit.RateLimitSnapshot;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RateLimitHeaderParser 单元测试 —— 验证上游限额响应头归一化逻辑。
 */
@DisplayName("RateLimitHeaderParser 测试")
class RateLimitHeaderParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("OpenAI OAuth Codex 响应头归一化为 5h/7d 用量窗口")
    void openAiCodexHeadersNormalizeToFiveHourAndSevenDayWindows() throws Exception {
        RateLimitHeaderParser parser = new RateLimitHeaderParser();
        HttpHeaders headers = HttpHeaders.of(Map.ofEntries(
                Map.entry("x-codex-active-limit", List.of("premium")),
                Map.entry("x-codex-credits-unlimited", List.of("False")),
                Map.entry("x-codex-primary-used-percent", List.of("12.5")),
                Map.entry("x-codex-primary-reset-after-seconds", List.of("14547")),
                Map.entry("x-codex-primary-reset-at", List.of("1780151096")),
                Map.entry("x-codex-primary-window-minutes", List.of("300")),
                Map.entry("x-codex-secondary-used-percent", List.of("70")),
                Map.entry("x-codex-secondary-reset-after-seconds", List.of("521737")),
                Map.entry("x-codex-secondary-reset-at", List.of("1780658287")),
                Map.entry("x-codex-secondary-window-minutes", List.of("10080")),
                Map.entry("x-codex-primary-over-secondary-limit-percent", List.of("0"))
        ), (name, value) -> true);

        RateLimitSnapshot snapshot = parser.parse(headers, Platform.OPENAI);

        assertTrue(snapshot.hasData());
        assertNotNull(snapshot.windowStart());
        assertEquals(Instant.ofEpochSecond(1780658287), snapshot.windowEnd());

        assertTrue(snapshot.statusJson().length() < 1000);
        JsonNode root = JSON.readTree(snapshot.statusJson());
        assertEquals("openai_oauth_codex", root.get("source").asText());
        assertEquals("premium", root.get("active_limit").asText());
        assertFalse(root.get("credits_unlimited").asBoolean());
        assertEquals(0.0, root.get("primary_over_secondary_limit_percent").asDouble());

        JsonNode fiveHour = root.get("windows").get(0);
        assertEquals("5h", fiveHour.get("label").asText());
        assertEquals("primary", fiveHour.get("scope").asText());
        assertEquals(300, fiveHour.get("window_minutes").asInt());
        assertEquals(12.5, fiveHour.get("used_percent").asDouble());
        assertEquals(87.5, fiveHour.get("remaining_percent").asDouble());
        assertEquals(14547, fiveHour.get("reset_after_seconds").asInt());
        assertEquals("2026-05-30T14:24:56Z", fiveHour.get("reset_at").asText());

        JsonNode sevenDay = root.get("windows").get(1);
        assertEquals("7d", sevenDay.get("label").asText());
        assertEquals("secondary", sevenDay.get("scope").asText());
        assertEquals(10080, sevenDay.get("window_minutes").asInt());
        assertEquals(70.0, sevenDay.get("used_percent").asDouble());
        assertEquals(30.0, sevenDay.get("remaining_percent").asDouble());
        assertEquals(521737, sevenDay.get("reset_after_seconds").asInt());
        assertEquals("2026-06-05T11:18:07Z", sevenDay.get("reset_at").asText());
    }

    @Test
    @DisplayName("Codex 缺少 used-percent 时保留刷新时间并标记用量未知")
    void openAiCodexHeadersKeepResetInfoWhenUsagePercentIsMissing() throws Exception {
        RateLimitHeaderParser parser = new RateLimitHeaderParser();
        HttpHeaders headers = HttpHeaders.of(Map.of(
                "x-codex-primary-reset-after-seconds", List.of("14547"),
                "x-codex-primary-reset-at", List.of("1780151096"),
                "x-codex-primary-window-minutes", List.of("300"),
                "x-codex-secondary-reset-after-seconds", List.of("521737"),
                "x-codex-secondary-reset-at", List.of("1780658287"),
                "x-codex-secondary-window-minutes", List.of("10080")
        ), (name, value) -> true);

        RateLimitSnapshot snapshot = parser.parse(headers, Platform.OPENAI);

        assertTrue(snapshot.hasData());
        JsonNode root = JSON.readTree(snapshot.statusJson());
        JsonNode fiveHour = root.get("windows").get(0);
        JsonNode sevenDay = root.get("windows").get(1);
        assertTrue(fiveHour.get("used_percent").isNull());
        assertTrue(fiveHour.get("remaining_percent").isNull());
        assertEquals("2026-05-30T14:24:56Z", fiveHour.get("reset_at").asText());
        assertTrue(sevenDay.get("used_percent").isNull());
        assertTrue(sevenDay.get("remaining_percent").isNull());
        assertEquals("2026-06-05T11:18:07Z", sevenDay.get("reset_at").asText());
    }
}
