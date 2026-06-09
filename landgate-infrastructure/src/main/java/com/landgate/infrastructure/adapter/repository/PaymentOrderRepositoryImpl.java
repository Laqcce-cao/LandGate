package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.payment.adapter.repository.IPaymentOrderRepository;
import com.landgate.domain.payment.model.entity.PaymentOrderEntity;
import com.landgate.infrastructure.adapter.mapper.PaymentOrderMapper;
import com.landgate.infrastructure.dao.IPaymentOrderDao;
import com.landgate.infrastructure.dao.po.PaymentOrderPO;
import com.landgate.types.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 支付订单仓储适配器实现 —— 实现 {@link IPaymentOrderRepository} 接口。
 * <p>
 * 委托 {@link IPaymentOrderDao} 进行数据访问，通过 {@link PaymentOrderMapper} 完成 PO ↔ Entity 映射。
 * 支付订单不使用软删除。
 */
@Component
@RequiredArgsConstructor
public class PaymentOrderRepositoryImpl implements IPaymentOrderRepository {

    private final IPaymentOrderDao paymentOrderDao;
    private final PaymentOrderMapper paymentOrderMapper;

    @Override
    public Optional<PaymentOrderEntity> findById(Long id) {
        return Optional.ofNullable(paymentOrderDao.selectById(id))
                .map(paymentOrderMapper::toEntity);
    }

    @Override
    public Optional<PaymentOrderEntity> findByOutTradeNo(String outTradeNo) {
        return Optional.ofNullable(paymentOrderDao.selectByOutTradeNo(outTradeNo))
                .map(paymentOrderMapper::toEntity);
    }

    @Override
    public Optional<PaymentOrderEntity> findByPaymentTradeNo(String paymentTradeNo) {
        return Optional.ofNullable(paymentOrderDao.selectByPaymentTradeNo(paymentTradeNo))
                .map(paymentOrderMapper::toEntity);
    }

    @Override
    public Optional<PaymentOrderEntity> findByRechargeCode(String rechargeCode) {
        return Optional.ofNullable(paymentOrderDao.selectByRechargeCode(rechargeCode))
                .map(paymentOrderMapper::toEntity);
    }

    @Override
    public List<PaymentOrderEntity> findByUserId(Long userId) {
        return paymentOrderDao.selectByUserId(userId).stream()
                .map(paymentOrderMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<PaymentOrderEntity> findByStatus(String status) {
        OrderStatus s = OrderStatus.valueOf(status.toUpperCase());
        return paymentOrderDao.selectByStatus(s.name()).stream()
                .map(paymentOrderMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<PaymentOrderEntity> findRechargeRecordsByUserId(Long userId, int offset, int size) {
        return paymentOrderDao.selectRechargeRecordsByUserId(userId, offset, size).stream()
                .map(paymentOrderMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public long countRechargeRecordsByUserId(Long userId) {
        return paymentOrderDao.countRechargeRecordsByUserId(userId);
    }

    @Override
    public List<PaymentOrderEntity> findByUserIdAndStatus(Long userId, String status) {
        OrderStatus s = OrderStatus.valueOf(status.toUpperCase());
        return paymentOrderDao.selectByUserIdAndStatus(userId, s.name()).stream()
                .map(paymentOrderMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<PaymentOrderEntity> findByStatusAndExpiresAtBefore(String status, Instant expiresAt) {
        OrderStatus s = OrderStatus.valueOf(status.toUpperCase());
        return paymentOrderDao.selectByStatusAndExpiresAtBefore(s.name(), expiresAt).stream()
                .map(paymentOrderMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public PaymentOrderEntity save(PaymentOrderEntity entity) {
        PaymentOrderPO po = paymentOrderMapper.toPO(entity);
        if (po.getId() == null) {
            paymentOrderDao.insert(po);
        } else {
            paymentOrderDao.update(po);
        }
        return paymentOrderMapper.toEntity(po);
    }

    @Override
    public List<PaymentOrderEntity> findAll() {
        return paymentOrderDao.selectAll().stream()
                .map(paymentOrderMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public long count() {
        return paymentOrderDao.countAll();
    }
}
