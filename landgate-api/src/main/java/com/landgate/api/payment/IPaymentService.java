package com.landgate.api.payment;

import com.landgate.api.payment.dto.*;
import java.util.List;

public interface IPaymentService {

    PaymentOrderDetail createBalanceOrder(Long userId, CreateOrderRequest req);
    PaymentOrderDetail createSubscriptionOrder(Long userId, CreateOrderRequest req);
    PaymentOrderDetail getOrder(Long userId, String orderId);
    PaymentOrderDetail getOrderByTradeNo(String tradeNo);
    List<PaymentOrderDetail> listOrders(Long userId, int page, int size);
    void cancelOrder(Long userId, String orderId);
    void requestRefund(Long userId, String orderId, String reason);
    PaymentOrderDetail confirmPayment(String tradeNo, String gatewayTradeNo, String providerKey);
    void expireOrders();
}
