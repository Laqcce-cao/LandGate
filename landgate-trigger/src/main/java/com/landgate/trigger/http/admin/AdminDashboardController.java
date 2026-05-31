package com.landgate.trigger.http.admin;

import com.landgate.api.billing.dto.*;
import com.landgate.domain.account.adapter.repository.IAccountRepository;
import com.landgate.domain.auth.adapter.repository.IApiKeyRepository;
import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.billing.adapter.repository.IUsageLogRepository;
import com.landgate.domain.payment.adapter.repository.IPaymentOrderRepository;
import com.landgate.domain.payment.model.entity.PaymentOrderEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;

/**
 * 管理员仪表盘控制器 —— 提供用户统计和收入概览数据。
 * <p>
 * 路由前缀：{@code /api/v1/admin/dashboard}，需要管理员 JWT 认证。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final IUserRepository userRepository;
    private final IPaymentOrderRepository paymentOrderRepository;
    private final IUsageLogRepository usageLogRepository;
    private final IApiKeyRepository apiKeyRepository;
    private final IAccountRepository accountRepository;

    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        long totalUsers = userRepository.count();
        long activeUsers = userRepository.countByStatus("active");
        long totalOrders = paymentOrderRepository.count();
        long completedOrders = paymentOrderRepository.findByStatus("completed").size();

        return ResponseEntity.ok(Map.of(
                "total_users", totalUsers,
                "active_users", activeUsers,
                "total_orders", totalOrders,
                "completed_orders", completedOrders
        ));
    }

    @GetMapping("/revenue")
    public ResponseEntity<?> getRevenue() {
        List<PaymentOrderEntity> completedOrders = paymentOrderRepository.findByStatus("completed");

        BigDecimal totalRevenue = completedOrders.stream()
                .map(PaymentOrderEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ResponseEntity.ok(Map.of(
                "total_revenue", totalRevenue,
                "completed_orders_count", completedOrders.size()
        ));
    }

    /**
     * 按用户聚合的用量排行。
     *
     * @param period 时间窗口：today（今日）或 month（本月）
     * @param sortBy 排序维度：totalCost（默认）或 totalTokens
     */
    @GetMapping("/user-usage")
    public ResponseEntity<?> getUserUsage(
            @RequestParam(defaultValue = "today") String period,
            @RequestParam(defaultValue = "totalCost") String sortBy
    ) {
        if (!"today".equals(period) && !"month".equals(period)) {
            return ResponseEntity.badRequest().body(Map.of("message", "period 仅支持 today 或 month"));
        }
        if (!"totalCost".equals(sortBy) && !"totalTokens".equals(sortBy)) {
            return ResponseEntity.badRequest().body(Map.of("message", "sortBy 仅支持 totalCost 或 totalTokens"));
        }

        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant start;
        if ("today".equals(period)) {
            ZonedDateTime todayStart = LocalDate.now(zone).atStartOfDay(zone);
            start = todayStart.toInstant();
        } else {
            ZonedDateTime monthStart = LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone);
            start = monthStart.toInstant();
        }
        Instant end = Instant.now();

        List<UserUsageSummary> result = usageLogRepository.aggregateUsageByUser(start, end, sortBy, "DESC");
        return ResponseEntity.ok(result);
    }

    /**
     * 仪表盘概览 —— 一次性返回所有统计数据（8张卡片 + token/cost 汇总）。
     */
    @GetMapping("/overview")
    public ResponseEntity<?> getOverview() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant now = Instant.now();

        // Row 1: Core Stats
        long totalApiKeys = apiKeyRepository.count();
        long activeApiKeys = apiKeyRepository.countByStatus("ACTIVE");
        long totalAccounts = accountRepository.count();
        long normalAccounts = accountRepository.countByStatus("active");
        long errorAccounts = totalAccounts - normalAccounts;
        long todayRequests = usageLogRepository.countByDateRange(todayStart, now);
        long totalRequests = usageLogRepository.count();
        long totalUsers = userRepository.count();
        long newUsersToday = userRepository.countByCreatedAtAfter(todayStart);

        // Row 2: Token Stats
        TokenCostSummary todaySummary = usageLogRepository.sumTokensAndCostByDateRange(todayStart, now);
        TokenCostSummary totalSummary = usageLogRepository.sumTokensAndCostByDateRange(Instant.EPOCH, now);
        double avgDuration = usageLogRepository.avgDurationByDateRange(todayStart, now);

        long minutesToday = Duration.between(todayStart, now).toMinutes();
        double rpm = minutesToday > 0 ? (double) todayRequests / minutesToday : 0;

        Map<String, Object> overview = new java.util.LinkedHashMap<>();
        overview.put("totalApiKeys", totalApiKeys);
        overview.put("activeApiKeys", activeApiKeys);
        overview.put("totalAccounts", totalAccounts);
        overview.put("normalAccounts", normalAccounts);
        overview.put("errorAccounts", errorAccounts);
        overview.put("todayRequests", todayRequests);
        overview.put("totalRequests", totalRequests);
        overview.put("newUsersToday", newUsersToday);
        overview.put("totalUsers", totalUsers);
        overview.put("todayTokens", todaySummary.totalTokens());
        overview.put("todayCost", todaySummary.totalCost());
        overview.put("totalTokens", totalSummary.totalTokens());
        overview.put("totalCost", totalSummary.totalCost());
        overview.put("avgDurationMs", avgDuration);
        overview.put("rpm", rpm);
        return ResponseEntity.ok(overview);
    }

    /**
     * 模型分布 —— 按模型聚合 token 和费用。
     */
    @GetMapping("/model-distribution")
    public ResponseEntity<?> getModelDistribution(
            @RequestParam(defaultValue = "7") int days) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant start = LocalDate.now(zone).minusDays(days).atStartOfDay(zone).toInstant();
        Instant end = Instant.now();
        List<ModelStats> result = usageLogRepository.aggregateByModel(start, end);
        return ResponseEntity.ok(result);
    }

    /**
     * Token 用量趋势 —— 按天聚合平台级 token 使用。
     */
    @GetMapping("/token-trend")
    public ResponseEntity<?> getTokenTrend(
            @RequestParam(defaultValue = "30") int days) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant start = LocalDate.now(zone).minusDays(days).atStartOfDay(zone).toInstant();
        Instant end = Instant.now();
        List<PlatformDailyStats> result = usageLogRepository.aggregatePlatformByDate(start, end);
        return ResponseEntity.ok(result);
    }

    /**
     * 用户用量趋势 —— Top N 用户的每日 token 使用。
     */
    @GetMapping("/user-trend")
    public ResponseEntity<?> getUserTrend(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "12") int topN) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant start = LocalDate.now(zone).minusDays(days).atStartOfDay(zone).toInstant();
        Instant end = Instant.now();
        List<UserDailyStats> result = usageLogRepository.aggregateTopUsersByDate(start, end, topN);
        return ResponseEntity.ok(result);
    }
}
