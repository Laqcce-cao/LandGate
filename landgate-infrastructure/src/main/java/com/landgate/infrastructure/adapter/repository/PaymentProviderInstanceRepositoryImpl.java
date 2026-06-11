package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.payment.adapter.repository.IPaymentProviderInstanceRepository;
import com.landgate.domain.payment.model.entity.PaymentProviderInstanceEntity;
import com.landgate.infrastructure.adapter.mapper.PaymentProviderInstanceMapper;
import com.landgate.infrastructure.dao.IPaymentProviderInstanceDao;
import com.landgate.infrastructure.dao.po.PaymentProviderInstancePO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 支付服务商实例仓储适配器实现 —— 实现 {@link IPaymentProviderInstanceRepository} 接口。
 * <p>
 * 委托 {@link IPaymentProviderInstanceDao} 进行数据访问，通过 {@link PaymentProviderInstanceMapper} 完成 PO ↔ Entity 映射。
 */
@Component
@RequiredArgsConstructor
public class PaymentProviderInstanceRepositoryImpl implements IPaymentProviderInstanceRepository {

    private final IPaymentProviderInstanceDao paymentProviderInstanceDao;
    private final PaymentProviderInstanceMapper paymentProviderInstanceMapper;

    @Override
    public Optional<PaymentProviderInstanceEntity> findById(Long id) {
        return Optional.ofNullable(paymentProviderInstanceDao.selectById(id))
                .map(paymentProviderInstanceMapper::toEntity);
    }

    @Override
    public List<PaymentProviderInstanceEntity> findByProviderKeyAndEnabledTrue(String providerKey) {
        return paymentProviderInstanceDao.selectByProviderKeyAndEnabled(providerKey).stream()
                .map(paymentProviderInstanceMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<PaymentProviderInstanceEntity> findByEnabledTrue() {
        return paymentProviderInstanceDao.selectEnabled().stream()
                .map(paymentProviderInstanceMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<PaymentProviderInstanceEntity> findByProviderKey(String providerKey) {
        return paymentProviderInstanceDao.selectByProviderKey(providerKey).stream()
                .map(paymentProviderInstanceMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public PaymentProviderInstanceEntity save(PaymentProviderInstanceEntity entity) {
        PaymentProviderInstancePO po = paymentProviderInstanceMapper.toPO(entity);
        if (po.getId() == null) {
            paymentProviderInstanceDao.insert(po);
        } else {
            paymentProviderInstanceDao.update(po);
        }
        return paymentProviderInstanceMapper.toEntity(po);
    }
}
