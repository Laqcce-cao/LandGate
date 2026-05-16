package com.landgate.trigger.http.payment;

import com.landgate.domain.payment.model.entity.PaymentOrderEntity;
import com.landgate.domain.payment.service.PaymentDomainService;
import com.landgate.types.enums.OrderStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 支付回调控制器 —— 接收支付平台的异步通知（Webhook）。
 * <p>
 * 路由前缀：{@code /api/v1/payment/webhook}，公开端点无需认证。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payment/webhook")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentDomainService paymentDomainService;

    @PostMapping("/notify")
    public ResponseEntity<?> handleNotify(@RequestBody Map<String, String> body,
                                           HttpServletRequest request) {
        String outTradeNo = body.get("out_trade_no");
        String tradeNo = body.get("trade_no");
        String amountStr = body.get("amount");
        String status = body.get("status");

        log.info("Payment webhook: out_trade_no={}, trade_no={}, amount={}, status={}",
                outTradeNo, tradeNo, amountStr, status);

        if (outTradeNo == null) {
            log.warn("Webhook missing out_trade_no");
            return ResponseEntity.ok("success");
        }

        try {
            PaymentOrderEntity order = paymentDomainService.getOrderByOutTradeNo(outTradeNo);
            if (order == null) {
                log.warn("Webhook received for unknown order: out_trade_no={}", outTradeNo);
                return ResponseEntity.ok("success");
            }

            if (!"SUCCESS".equalsIgnoreCase(status)) {
                log.info("Webhook non-success status ignored: out_trade_no={}, status={}", outTradeNo, status);
                return ResponseEntity.ok("success");
            }

            if (OrderStatus.PAID == order.getStatus() || OrderStatus.COMPLETED == order.getStatus()) {
                log.info("Order already paid/completed: out_trade_no={}, status={}", outTradeNo, order.getStatus());
                return ResponseEntity.ok("success");
            }

            BigDecimal payAmount = amountStr != null ? new BigDecimal(amountStr) : null;
            paymentDomainService.confirmPayment(order.getId(), tradeNo, payAmount);
            return ResponseEntity.ok("success");
        } catch (Exception e) {
            log.error("Webhook handling failed: out_trade_no={}", outTradeNo, e);
            return ResponseEntity.status(500).body(Map.of("error", "handle_failed"));
        }
    }

    @PostMapping("/stripe")
    public ResponseEntity<?> stripeWebhook(@RequestBody String body,
                                            HttpServletRequest request) {
        log.info("Stripe webhook received");
        return ResponseEntity.ok("");
    }

    @PostMapping("/alipay")
    public ResponseEntity<?> alipayNotify(HttpServletRequest request) {
        log.info("Alipay webhook received");
        return ResponseEntity.ok("success");
    }

    @PostMapping("/wxpay")
    public ResponseEntity<?> wxpayNotify(@RequestBody String body,
                                          HttpServletRequest request) {
        log.info("Wxpay webhook received");
        return ResponseEntity.ok(Map.of("code", "SUCCESS", "message", "成功"));
    }

    @PostMapping("/easypay")
    public ResponseEntity<?> easypayNotify(HttpServletRequest request) {
        log.info("EasyPay webhook received");
        return ResponseEntity.ok("success");
    }

    @PostMapping("/airwallex")
    public ResponseEntity<?> airwallexWebhook(@RequestBody String body,
                                               HttpServletRequest request) {
        log.info("Airwallex webhook received");
        return ResponseEntity.ok("");
    }
}
