package com.landgate.domain.payment.service;

import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.payment.adapter.repository.IPaymentOrderRepository;
import com.landgate.domain.payment.adapter.repository.IPaymentProviderInstanceRepository;
import com.landgate.domain.payment.model.entity.PaymentOrderEntity;
import com.landgate.domain.payment.model.entity.PaymentProviderInstanceEntity;
import com.landgate.types.enums.OrderStatus;
import com.landgate.types.enums.OrderType;
import com.landgate.types.enums.PaymentType;
import com.landgate.types.exception.BusinessException;
import com.landgate.types.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

/**
 * 支付领域服务 —— 支付订单的创建、确认、取消、退款和过期处理。
 * <p>
 * 支持余额充值订单和订阅订单两种类型。订单创建后 30 分钟未支付自动过期。
 * 支付确认后自动执行余额履约（rechargeCode 非空的余额订单立即充值到账）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentDomainService {

    private final IPaymentOrderRepository orderRepository;
    private final IPaymentProviderInstanceRepository providerRepository;
    private final IUserRepository userRepository;

    private static final int ORDER_EXPIRE_MINUTES = 30;
    private static final BigDecimal FEE_RATE = new BigDecimal("0.05");

    /**
     * 创建余额充值订单。
     *
     * @param userId      用户 ID
     * @param userEmail   用户邮箱
     * @param amount      充值金额
     * @param paymentType 支付方式
     * @param clientIp    客户端 IP
     * @return 创建的订单实体
     */
    @Transactional
    public PaymentOrderEntity createBalanceOrder(Long userId, String userEmail, BigDecimal amount,
                                                   PaymentType paymentType, String clientIp) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        BigDecimal payAmount = amount;
        BigDecimal feeRate = BigDecimal.ZERO;
        String rechargeCode = generateRechargeCode();

        PaymentOrderEntity order = PaymentOrderEntity.builder()
                .userId(userId).userEmail(userEmail != null ? userEmail : user.getEmail())
                .amount(amount).payAmount(payAmount).rechargeCode(rechargeCode)
                .outTradeNo(generateOutTradeNo()).paymentType(paymentType)
                .status(OrderStatus.PENDING)
                .feeRate(feeRate).clientIp(clientIp)
                .expiresAt(Instant.now().plusSeconds(ORDER_EXPIRE_MINUTES * 60L))
                .build();
        order = orderRepository.save(order);
        log.info("Balance order created: id={}, user_id={}, amount={}", order.getId(), userId, amount);
        return order;
    }

    /**
     * 创建订阅订单。
     *
     * @param userId              用户 ID
     * @param userEmail           用户邮箱
     * @param planId              订阅计划 ID
     * @param subscriptionGroupId 订阅分组 ID
     * @param amount              支付金额
     * @param paymentType         支付方式
     * @param clientIp            客户端 IP
     * @return 创建的订单实体
     */
    @Transactional
    public PaymentOrderEntity createSubscriptionOrder(Long userId, String userEmail, Long planId,
                                                        Long subscriptionGroupId, BigDecimal amount,
                                                        PaymentType paymentType, String clientIp) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        PaymentOrderEntity order = PaymentOrderEntity.builder()
                .userId(userId).userEmail(userEmail != null ? userEmail : user.getEmail())
                .amount(amount).payAmount(amount)
                .outTradeNo(generateOutTradeNo()).paymentType(paymentType)
                .status(OrderStatus.PENDING)
                .planId(planId).subscriptionGroupId(subscriptionGroupId)
                .feeRate(BigDecimal.ZERO).clientIp(clientIp)
                .expiresAt(Instant.now().plusSeconds(ORDER_EXPIRE_MINUTES * 60L))
                .build();
        order = orderRepository.save(order);
        log.info("Subscription order created: id={}, user_id={}, plan_id={}", order.getId(), userId, planId);
        return order;
    }

    /**
     * 管理员手动充值 —— 增加余额并写入已完成的充值订单，供用户查询充值记录。
     *
     * @param userId   用户 ID
     * @param amount   充值金额（正数，USD）
     * @param operator 操作人标识
     * @return 创建的充值订单
     */
    @Transactional
    public PaymentOrderEntity adminRecharge(Long userId, BigDecimal amount, String operator) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("INVALID_RECHARGE_AMOUNT", "充值金额必须大于 0");
        }
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        BigDecimal currentBalance = user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO;
        BigDecimal currentRecharged = user.getTotalRecharged() != null ? user.getTotalRecharged() : BigDecimal.ZERO;
        BigDecimal normalizedAmount = amount.setScale(4, RoundingMode.HALF_UP);
        Instant now = Instant.now();

        user.setBalance(currentBalance.add(normalizedAmount).setScale(4, RoundingMode.HALF_UP));
        user.setTotalRecharged(currentRecharged.add(normalizedAmount).setScale(4, RoundingMode.HALF_UP));
        userRepository.save(user);

        PaymentOrderEntity order = PaymentOrderEntity.builder()
                .userId(user.getId())
                .userEmail(user.getEmail())
                .userName(user.getUsername() != null ? user.getUsername() : "")
                .userNotes(user.getNotes())
                .amount(normalizedAmount)
                .payAmount(normalizedAmount)
                .feeRate(BigDecimal.ZERO)
                .outTradeNo(generateOutTradeNo())
                .paymentType(PaymentType.MANUAL)
                .paymentTradeNo(buildManualTradeNo(operator))
                .orderType(OrderType.BALANCE)
                .providerKey("manual")
                .status(OrderStatus.COMPLETED)
                .refundAmount(BigDecimal.ZERO)
                .expiresAt(now)
                .paidAt(now)
                .completedAt(now)
                .clientIp("")
                .srcHost("")
                .build();
        order = orderRepository.save(order);

        log.info("Admin recharged user: user_id={}, amount={}, new_balance={}, order_id={}",
                user.getId(), normalizedAmount, user.getBalance(), order.getId());
        return order;
    }

    /**
     * 确认支付 —— 将订单状态改为 PAID，并执行余额履约。
     *
     * @param orderId        订单 ID
     * @param paymentTradeNo 支付平台交易号
     * @param payAmount      实际支付金额
     * @throws BusinessException 订单非待支付状态时抛出
     */
    @Transactional
    public void confirmPayment(Long orderId, String paymentTradeNo, BigDecimal payAmount) {
        PaymentOrderEntity order = getOrder(orderId);
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException("ORDER_NOT_PENDING", "Order is not in pending status");
        }
        order.setPaymentTradeNo(paymentTradeNo);
        order.setPayAmount(payAmount != null ? payAmount : order.getPayAmount());
        order.setStatus(OrderStatus.PAID);
        order.setPaidAt(Instant.now());
        orderRepository.save(order);

        if (order.getRechargeCode() != null) {
            executeBalanceFulfillment(order);
        }
        log.info("Payment confirmed: order_id={}, trade_no={}, amount={}", orderId, paymentTradeNo, payAmount);
    }

    private void executeBalanceFulfillment(PaymentOrderEntity order) {
        userRepository.findById(order.getUserId()).ifPresent(user -> {
            user.setBalance(user.getBalance().add(order.getAmount()));
            user.setTotalRecharged(user.getTotalRecharged().add(order.getAmount()));
            userRepository.save(user);
            log.info("Balance fulfilled: user_id={}, amount={}, new_balance={}",
                    user.getId(), order.getAmount(), user.getBalance());
        });
    }

    /**
     * 取消订单 —— 仅待支付状态的订单可取消。
     *
     * @param orderId 订单 ID
     * @throws BusinessException 订单非待支付状态时抛出
     */
    @Transactional
    public void cancelOrder(Long orderId) {
        PaymentOrderEntity order = getOrder(orderId);
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException("ORDER_NOT_PENDING", "Only pending orders can be cancelled");
        }
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        log.info("Order cancelled: id={}", orderId);
    }

    /**
     * 申请退款 —— 仅已支付状态的订单可申请退款，状态改为 REFUNDING。
     *
     * @param orderId 订单 ID
     * @throws BusinessException 订单非已支付状态时抛出
     */
    @Transactional
    public void requestRefund(Long orderId) {
        PaymentOrderEntity order = getOrder(orderId);
        if (order.getStatus() != OrderStatus.PAID) {
            throw new BusinessException("ORDER_NOT_PAID", "Only paid orders can be refunded");
        }
        order.setStatus(OrderStatus.REFUNDING);
        orderRepository.save(order);
        log.info("Refund requested: order_id={}", orderId);
    }

    /**
     * 批量过期超时未支付订单 —— 将超过 30 分钟的 PENDING 订单标记为 EXPIRED。
     *
     * @return 过期的订单数量
     */
    @Transactional
    public int expireOrders() {
        List<PaymentOrderEntity> expired = orderRepository.findByStatusAndExpiresAtBefore(
                OrderStatus.PENDING.name().toLowerCase(), Instant.now());
        for (PaymentOrderEntity o : expired) {
            o.setStatus(OrderStatus.EXPIRED);
            orderRepository.save(o);
        }
        if (!expired.isEmpty()) log.info("Expired {} orders", expired.size());
        return expired.size();
    }

    /**
     * 按 ID 查询订单。
     *
     * @param id 订单 ID
     * @return 订单实体
     * @throws NotFoundException 订单不存在时抛出
     */
    public PaymentOrderEntity getOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Order not found: " + id));
    }

    /**
     * 按外部交易号查询订单。
     *
     * @param outTradeNo 外部交易号
     * @return 订单实体
     * @throws NotFoundException 订单不存在时抛出
     */
    public PaymentOrderEntity getOrderByOutTradeNo(String outTradeNo) {
        return orderRepository.findByOutTradeNo(outTradeNo)
                .orElseThrow(() -> new NotFoundException("Order not found: " + outTradeNo));
    }

    /**
     * 查询用户的所有订单。
     *
     * @param userId 用户 ID
     * @return 订单列表
     */
    public List<PaymentOrderEntity> listUserOrders(Long userId) {
        return orderRepository.findByUserId(userId);
    }

    /**
     * 获取所有启用的支付服务商实例。
     *
     * @return 启用的支付服务商列表
     */
    public List<PaymentProviderInstanceEntity> getEnabledProviders() {
        return providerRepository.findByEnabledTrue();
    }

    private String generateOutTradeNo() {
        return "LG" + System.currentTimeMillis() + randomDigits(6);
    }

    private String generateRechargeCode() {
        return "RC-" + randomAlphanumeric(16);
    }

    private String buildManualTradeNo(String operator) {
        String suffix = (operator == null || operator.isBlank()) ? "admin" : operator.trim();
        return "ADMIN-" + suffix + "-" + System.currentTimeMillis();
    }

    private String randomDigits(int len) {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(r.nextInt(10));
        return sb.toString();
    }

    private String randomAlphanumeric(int len) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }
}
