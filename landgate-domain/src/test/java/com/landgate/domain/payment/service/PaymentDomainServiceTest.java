package com.landgate.domain.payment.service;

import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.payment.adapter.repository.IPaymentOrderRepository;
import com.landgate.domain.payment.adapter.repository.IPaymentProviderInstanceRepository;
import com.landgate.domain.payment.model.entity.PaymentOrderEntity;
import com.landgate.types.enums.OrderStatus;
import com.landgate.types.enums.OrderType;
import com.landgate.types.enums.PaymentType;
import com.landgate.types.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 支付领域服务测试 —— 验证管理员手动充值会写入用户可见的充值记录。
 */
@DisplayName("PaymentDomainService 测试")
class PaymentDomainServiceTest {

    @Test
    @DisplayName("管理员充值会增加余额并创建已完成的手动充值订单")
    void adminRechargeCreatesVisiblePaymentOrder() {
        CapturingPaymentOrderRepository orderRepository = new CapturingPaymentOrderRepository();
        CapturingUserRepository userRepository = new CapturingUserRepository(UserEntity.builder()
                .id(7L)
                .email("user@example.com")
                .username("demo")
                .balance(new BigDecimal("1.25"))
                .totalRecharged(new BigDecimal("2.00"))
                .build());
        PaymentDomainService service = new PaymentDomainService(
                orderRepository, mock(IPaymentProviderInstanceRepository.class), userRepository);

        PaymentOrderEntity order = service.adminRecharge(7L, new BigDecimal("3.45678"), "root");

        assertEquals(new BigDecimal("4.7068"), userRepository.savedUser.getBalance());
        assertEquals(new BigDecimal("5.4568"), userRepository.savedUser.getTotalRecharged());
        assertEquals(1, orderRepository.savedOrders.size());
        assertSame(order, orderRepository.savedOrders.get(0));
        assertEquals(7L, order.getUserId());
        assertEquals(new BigDecimal("3.4568"), order.getAmount());
        assertEquals(new BigDecimal("3.4568"), order.getPayAmount());
        assertEquals(OrderType.BALANCE, order.getOrderType());
        assertEquals(PaymentType.MANUAL, order.getPaymentType());
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertNotNull(order.getPaidAt());
        assertNotNull(order.getCompletedAt());
        assertTrue(order.getPaymentTradeNo().startsWith("ADMIN-root-"));
    }

    @Test
    @DisplayName("管理员充值金额必须大于 0")
    void adminRechargeRejectsNonPositiveAmount() {
        PaymentDomainService service = new PaymentDomainService(
                new CapturingPaymentOrderRepository(),
                mock(IPaymentProviderInstanceRepository.class),
                new CapturingUserRepository(UserEntity.builder().id(7L).build()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.adminRecharge(7L, BigDecimal.ZERO, "root"));

        assertEquals("INVALID_RECHARGE_AMOUNT", ex.getErrorCode());
    }

    private static class CapturingUserRepository implements IUserRepository {
        private UserEntity user;
        private UserEntity savedUser;

        CapturingUserRepository(UserEntity user) {
            this.user = user;
        }

        @Override
        public Optional<UserEntity> findByEmail(String email) { return Optional.empty(); }

        @Override
        public Optional<UserEntity> findById(Long id) { return Optional.ofNullable(user); }

        @Override
        public UserEntity save(UserEntity entity) {
            this.user = entity;
            this.savedUser = entity;
            return entity;
        }

        @Override
        public boolean existsByEmail(String email) { return false; }

        @Override
        public long countByStatus(String status) { return 0; }

        @Override
        public long count() { return 0; }

        @Override
        public List<UserEntity> findBySearch(String search, int page, int pageSize) { return List.of(); }

        @Override
        public long countBySearch(String search) { return 0; }

        @Override
        public int updateBalance(Long id, BigDecimal newBalance) { return 0; }

        @Override
        public long countByCreatedAtAfter(Instant after) { return 0; }
    }

    private static class CapturingPaymentOrderRepository implements IPaymentOrderRepository {
        private final List<PaymentOrderEntity> savedOrders = new ArrayList<>();

        @Override
        public Optional<PaymentOrderEntity> findById(Long id) { return Optional.empty(); }

        @Override
        public Optional<PaymentOrderEntity> findByOutTradeNo(String outTradeNo) { return Optional.empty(); }

        @Override
        public Optional<PaymentOrderEntity> findByPaymentTradeNo(String paymentTradeNo) { return Optional.empty(); }

        @Override
        public Optional<PaymentOrderEntity> findByRechargeCode(String rechargeCode) { return Optional.empty(); }

        @Override
        public List<PaymentOrderEntity> findByUserId(Long userId) { return List.of(); }

        @Override
        public List<PaymentOrderEntity> findByStatus(String status) { return List.of(); }

        @Override
        public List<PaymentOrderEntity> findByUserIdAndStatus(Long userId, String status) { return List.of(); }

        @Override
        public List<PaymentOrderEntity> findByStatusAndExpiresAtBefore(String status, Instant expiresAt) { return List.of(); }

        @Override
        public PaymentOrderEntity save(PaymentOrderEntity entity) {
            entity.setId((long) savedOrders.size() + 1);
            savedOrders.add(entity);
            return entity;
        }

        @Override
        public List<PaymentOrderEntity> findAll() { return savedOrders; }

        @Override
        public long count() { return savedOrders.size(); }
    }
}
