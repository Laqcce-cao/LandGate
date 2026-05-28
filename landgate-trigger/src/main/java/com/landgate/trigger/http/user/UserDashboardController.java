package com.landgate.trigger.http.user;

import com.landgate.api.billing.dto.*;
import com.landgate.domain.billing.adapter.repository.IUsageLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户仪表盘控制器 —— 提供当前用户的用量统计数据。
 * <p>
 * 路由前缀：{@code /api/v1/user/dashboard}，需要 JWT 认证。
 * 自动从 JWT 中提取当前用户 ID。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/user/dashboard")
@RequiredArgsConstructor
public class UserDashboardController {

    private final IUsageLogRepository usageLogRepository;

    /**
     * 用户仪表盘概览 —— 一次性返回当前用户的所有统计数据。
     */
    @GetMapping("/overview")
    public ResponseEntity<?> getOverview(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("user_id");
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error_code", "UNAUTHORIZED", "message", "User not authenticated"));
        }

        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant now = Instant.now();

        // Today stats
        long todayRequests = usageLogRepository.countByUserIdAndDateRange(userId, todayStart, now);
        TokenCostSummary todaySummary = usageLogRepository.sumTokensAndCostByUserIdAndDateRange(userId, todayStart, now);

        // All-time stats
        long totalRequests = usageLogRepository.countByUserId(userId);
        TokenCostSummary totalSummary = usageLogRepository.sumTokensAndCostByUserIdAndDateRange(userId, Instant.EPOCH, now);

        // Avg daily stats (last 30 days)
        Instant thirtyDaysAgo = LocalDate.now(zone).minusDays(30).atStartOfDay(zone).toInstant();
        long daysElapsed = Duration.between(thirtyDaysAgo, now).toDays();
        if (daysElapsed < 1) daysElapsed = 1;
        TokenCostSummary last30Days = usageLogRepository.sumTokensAndCostByUserIdAndDateRange(userId, thirtyDaysAgo, now);
        double avgDailyTokens = (double) last30Days.totalTokens() / daysElapsed;
        double avgDailyRequests = (double) usageLogRepository.countByUserIdAndDateRange(userId, thirtyDaysAgo, now) / daysElapsed;

        double avgDuration = usageLogRepository.avgDurationByUserIdAndDateRange(userId, todayStart, now);

        long minutesToday = Duration.between(todayStart, now).toMinutes();
        double rpm = minutesToday > 0 ? (double) todayRequests / minutesToday : 0;

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalRequests", totalRequests);
        overview.put("todayRequests", todayRequests);
        overview.put("totalTokens", totalSummary.totalTokens());
        overview.put("totalCost", totalSummary.totalCost());
        overview.put("todayTokens", todaySummary.totalTokens());
        overview.put("todayCost", todaySummary.totalCost());
        overview.put("avgDailyTokens", Math.round(avgDailyTokens));
        overview.put("avgDailyRequests", Math.round(avgDailyRequests * 10.0) / 10.0);
        overview.put("avgDurationMs", avgDuration);
        overview.put("rpm", rpm);
        return ResponseEntity.ok(overview);
    }

    /**
     * 用户模型分布 —— 按模型聚合当前用户的 token 和费用。
     * <p>
     * 支持两种时间指定方式：
     * 1. days=7 —— 近 N 天（默认 7）
     * 2. start=2026-05-01&amp;end=2026-05-29 —— 自定义日期范围（包含 start，不包含 end）
     */
    @GetMapping("/model-distribution")
    public ResponseEntity<?> getModelDistribution(
            HttpServletRequest request,
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        Long userId = (Long) request.getAttribute("user_id");
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error_code", "UNAUTHORIZED", "message", "User not authenticated"));
        }

        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant startInstant;
        Instant endInstant = Instant.now();

        if (start != null && !start.isBlank() && end != null && !end.isBlank()) {
            startInstant = LocalDate.parse(start).atStartOfDay(zone).toInstant();
            endInstant = LocalDate.parse(end).plusDays(1).atStartOfDay(zone).toInstant();
        } else {
            int d = (days != null) ? days : 7;
            startInstant = LocalDate.now(zone).minusDays(d).atStartOfDay(zone).toInstant();
        }

        List<ModelStats> result = usageLogRepository.aggregateByUserIdAndModel(userId, startInstant, endInstant);
        return ResponseEntity.ok(result);
    }

    /**
     * 用户 Token 用量趋势 —— 按天聚合当前用户的 token 使用。
     * <p>
     * 支持两种时间指定方式：
     * 1. days=30 —— 近 N 天（默认 30）
     * 2. start=2026-05-01&amp;end=2026-05-29 —— 自定义日期范围（包含 start，不包含 end）
     */
    @GetMapping("/token-trend")
    public ResponseEntity<?> getTokenTrend(
            HttpServletRequest request,
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        Long userId = (Long) request.getAttribute("user_id");
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error_code", "UNAUTHORIZED", "message", "User not authenticated"));
        }

        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate startDate;
        LocalDate endDate;

        if (start != null && !start.isBlank() && end != null && !end.isBlank()) {
            startDate = LocalDate.parse(start);
            endDate = LocalDate.parse(end).plusDays(1);
        } else {
            int d = (days != null) ? days : 30;
            startDate = LocalDate.now(zone).minusDays(d);
            endDate = LocalDate.now(zone).plusDays(1);
        }

        List<DailyUsageStats> result = usageLogRepository.aggregateByUserAndDate(userId, startDate, endDate);
        return ResponseEntity.ok(result);
    }
}
