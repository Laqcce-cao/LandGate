package com.landgate.trigger.http.payment;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 支付回调控制器 —— 接收支付平台的异步通知（Webhook）。
 * <p>
 * 路由前缀：{@code /api/v1/payment/webhook}，公开端点无需认证。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payment/webhook")
public class PaymentWebhookController {

    @PostMapping("/notify")
    public ResponseEntity<?> handleNotify(@RequestBody Map<String, String> body,
                                           HttpServletRequest request) {
        log.warn("Payment webhook notify is disabled: remote_addr={}", request.getRemoteAddr());
        return ResponseEntity.status(501).body(Map.of("error", "payment_webhook_disabled"));
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
