package com.landgate.trigger.http.admin;

import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.payment.adapter.repository.IPaymentOrderRepository;
import com.landgate.domain.payment.model.entity.PaymentOrderEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
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
}
