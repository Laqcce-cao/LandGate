package com.landgate.trigger.http.admin;

import com.landgate.api.billing.dto.UserUsageSummary;
import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.billing.adapter.repository.IUsageLogRepository;
import com.landgate.domain.payment.adapter.repository.IPaymentOrderRepository;
import com.landgate.domain.payment.model.entity.PaymentOrderEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
}
