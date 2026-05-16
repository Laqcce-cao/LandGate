package com.landgate.trigger.http.admin;

import com.landgate.api.admin.dto.AdminDTOs.AdminRefundRequest;
import com.landgate.api.admin.dto.AdminDTOs.ConfirmRequest;
import com.landgate.domain.payment.adapter.repository.IPaymentOrderRepository;
import com.landgate.domain.payment.model.entity.PaymentOrderEntity;
import com.landgate.domain.payment.service.PaymentDomainService;
import com.landgate.types.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 支付管理控制器 —— 管理员订单管理、确认和退款操作。
 * <p>
 * 路由前缀：{@code /api/v1/admin/payments}，需要管理员 JWT 认证。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/payments")
@RequiredArgsConstructor
public class PaymentAdminController {

    private final IPaymentOrderRepository paymentOrderRepository;
    private final PaymentDomainService paymentDomainService;

    @GetMapping
    public ResponseEntity<?> listOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<PaymentOrderEntity> orders;
        if (status != null && !status.isEmpty()) {
            orders = paymentOrderRepository.findByStatus(status.toLowerCase());
        } else {
            orders = paymentOrderRepository.findAll();
        }
        int start = page * size;
        int end = Math.min(start + size, orders.size());
        if (start >= orders.size()) {
            return ResponseEntity.ok(Map.of("orders", List.of(), "total", orders.size()));
        }
        log.debug("Admin list payment orders: status={}, page={}, size={}", status, page, size);
        return ResponseEntity.ok(Map.of(
                "orders", orders.subList(start, end),
                "total", orders.size(),
                "page", page,
                "size", size
        ));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<?> getOrder(@PathVariable Long orderId) {
        PaymentOrderEntity order = paymentDomainService.getOrder(orderId);
        return ResponseEntity.ok(Map.of("order", order, "audit_logs", List.of()));
    }

    @PostMapping("/{orderId}/confirm")
    public ResponseEntity<?> confirmPayment(@PathVariable Long orderId,
                                             @RequestBody ConfirmRequest req) {
        log.info("Admin confirm payment: order_id={}, trade_no={}", orderId, req.tradeNo());
        paymentDomainService.confirmPayment(orderId, req.tradeNo(), req.payAmount());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/{orderId}/refund")
    public ResponseEntity<?> adminRefund(@PathVariable Long orderId,
                                          @RequestBody AdminRefundRequest req) {
        log.info("Admin refund: order_id={}, reason={}", orderId, req.reason());
        PaymentOrderEntity order = paymentDomainService.getOrder(orderId);

        if (OrderStatus.COMPLETED != order.getStatus()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Only completed orders can be refunded"));
        }

        order.setStatus(OrderStatus.REFUNDING);
        order.setRefundReason(req.reason());
        order.setRefundRequestedBy("admin");
        order.setForceRefund(req.forceRefund() != null && req.forceRefund());
        paymentOrderRepository.save(order);

        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/refunds/pending")
    public ResponseEntity<?> listPendingRefunds() {
        List<PaymentOrderEntity> refunds = new ArrayList<>();
        refunds.addAll(paymentOrderRepository.findByStatus("refunding"));
        return ResponseEntity.ok(Map.of("orders", refunds, "total", refunds.size()));
    }

    @GetMapping("/{orderId}/logs")
    public ResponseEntity<?> getAuditLogs(@PathVariable Long orderId) {
        return ResponseEntity.ok(Map.of("logs", List.of()));
    }
}
