package com.landgate.trigger.http.payment;

import com.landgate.api.payment.dto.PaymentDTOs.CreateBalanceOrderRequest;
import com.landgate.api.payment.dto.PaymentDTOs.CreateSubscriptionOrderRequest;
import com.landgate.api.payment.dto.PaymentDTOs.RefundRequest;
import com.landgate.api.payment.dto.RechargeRecordDTO;
import com.landgate.types.response.PageResponse;
import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.payment.model.entity.PaymentOrderEntity;
import com.landgate.domain.payment.service.PaymentDomainService;
import com.landgate.types.enums.PaymentType;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 用户支付控制器 —— 余额充值、订阅购买等支付订单的创建和查询。
 * <p>
 * 路由前缀：{@code /api/v1/payment}，需要 JWT 认证。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentDomainService paymentDomainService;
    private final IUserRepository userRepository;

    @PostMapping("/order/balance")
    public ResponseEntity<?> createBalanceOrder(@RequestBody CreateBalanceOrderRequest req,
                                                 HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        if (userId == null || userId == 0L) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        log.info("Create balance order: user_id={}, amount={}, type={}", userId, req.amount(), req.paymentType());

        PaymentType paymentType;
        try {
            paymentType = PaymentType.valueOf(req.paymentType().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid payment type: " + req.paymentType()));
        }

        PaymentOrderEntity order = paymentDomainService.createBalanceOrder(
                userId, null, req.amount(), paymentType, getClientIp(request));
        return ResponseEntity.ok(Map.of("order", order));
    }

    @PostMapping("/order/subscription")
    public ResponseEntity<?> createSubscriptionOrder(@RequestBody CreateSubscriptionOrderRequest req,
                                                      HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        if (userId == null || userId == 0L) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        log.info("Create subscription order: user_id={}, plan_id={}, group_id={}",
                userId, req.planId(), req.groupId());

        PaymentType paymentType;
        try {
            paymentType = PaymentType.valueOf(req.paymentType().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid payment type: " + req.paymentType()));
        }

        PaymentOrderEntity order = paymentDomainService.createSubscriptionOrder(
                userId, null, req.planId(), req.groupId(), req.amount(), paymentType, getClientIp(request));
        return ResponseEntity.ok(Map.of("order", order));
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<?> getOrder(@PathVariable Long orderId,
                                       HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        PaymentOrderEntity order = paymentDomainService.getOrder(orderId);

        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user != null && !user.isAdmin() && !order.getUserId().equals(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        }

        return ResponseEntity.ok(Map.of("order", order));
    }

    @GetMapping("/orders")
    public ResponseEntity<?> listUserOrders(HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        if (userId == null || userId == 0L) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        List<PaymentOrderEntity> orders = paymentDomainService.listUserOrders(userId);
        return ResponseEntity.ok(Map.of("orders", orders, "total", orders.size()));
    }

    /**
     * 查询当前登录用户的充值记录。
     * <p>
     * 仅返回 payment_orders 中属于当前用户的余额订单，避免用户看到其他用户订单或支付内部字段。
     */
    @GetMapping("/recharge-records")
    public ResponseEntity<?> listRechargeRecords(@RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        if (userId == null || userId == 0L) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }

        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        List<RechargeRecordDTO> records = paymentDomainService
                .listUserRechargeRecords(userId, normalizedPage, normalizedSize)
                .stream()
                .map(this::toRechargeRecordDTO)
                .toList();
        long total = paymentDomainService.countUserRechargeRecords(userId);

        return ResponseEntity.ok(PageResponse.of(records, normalizedPage, normalizedSize, total));
    }

    @PostMapping("/order/{orderId}/cancel")
    public ResponseEntity<?> cancelOrder(@PathVariable Long orderId,
                                          HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        if (userId == null || userId == 0L) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        paymentDomainService.cancelOrder(orderId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/order/{orderId}/refund")
    public ResponseEntity<?> requestRefund(@PathVariable Long orderId,
                                            @RequestBody RefundRequest req,
                                            HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        if (userId == null || userId == 0L) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        paymentDomainService.requestRefund(orderId);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/order/{orderId}/logs")
    public ResponseEntity<?> getAuditLogs(@PathVariable Long orderId,
                                           HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        PaymentOrderEntity order = paymentDomainService.getOrder(orderId);

        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user != null && !user.isAdmin() && !order.getUserId().equals(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        }

        return ResponseEntity.ok(Map.of("logs", List.of()));
    }

    /**
     * 将支付订单实体转换为用户侧充值记录 DTO。
     */
    private RechargeRecordDTO toRechargeRecordDTO(PaymentOrderEntity order) {
        return new RechargeRecordDTO(
                order.getId(),
                order.getAmount(),
                order.getPayAmount(),
                order.getPaymentType() != null ? order.getPaymentType().name() : null,
                order.getStatus() != null ? order.getStatus().name() : null,
                order.getOutTradeNo(),
                order.getPaymentTradeNo(),
                order.getProviderKey(),
                order.getPaidAt(),
                order.getCompletedAt(),
                order.getCreatedAt()
        );
    }

    private Long getUserIdFromRequest(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("user_id");
        if (userId != null) return userId;

        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof UserEntity user) {
            return user.getId();
        }
        return 0L;
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) return realIp;
        return request.getRemoteAddr();
    }
}
