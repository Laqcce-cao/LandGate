package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.marketing.adapter.repository.IPromoCodeRepository;
import com.landgate.domain.marketing.model.entity.PromoCodeEntity;
import com.landgate.infrastructure.adapter.mapper.PromoCodeMapper;
import com.landgate.infrastructure.dao.IPromoCodeDao;
import com.landgate.infrastructure.dao.po.PromoCodePO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 优惠码仓储适配器实现 —— 实现 {@link IPromoCodeRepository} 接口。
 * <p>
 * 委托 {@link IPromoCodeDao} 进行数据访问，通过 {@link PromoCodeMapper} 完成 PO ↔ Entity 映射。
 * 使用软删除策略。
 */
@Component
@RequiredArgsConstructor
public class PromoCodeRepositoryImpl implements IPromoCodeRepository {

    private final IPromoCodeDao promoCodeDao;
    private final PromoCodeMapper promoCodeMapper;

    @Override
    public Optional<PromoCodeEntity> findById(Long id) {
        return Optional.ofNullable(promoCodeDao.selectById(id))
                .filter(po -> po.getDeletedAt() == null)
                .map(promoCodeMapper::toEntity);
    }

    @Override
    public Optional<PromoCodeEntity> findByCode(String code) {
        return Optional.ofNullable(promoCodeDao.selectByCode(code))
                .filter(po -> po.getDeletedAt() == null)
                .map(promoCodeMapper::toEntity);
    }

    @Override
    public List<PromoCodeEntity> findAll() {
        return promoCodeDao.selectAll().stream()
                .map(promoCodeMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<PromoCodeEntity> findByEnabledTrue() {
        return promoCodeDao.selectEnabled().stream()
                .map(promoCodeMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public PromoCodeEntity save(PromoCodeEntity entity) {
        PromoCodePO po = promoCodeMapper.toPO(entity);
        if (po.getId() == null) {
            promoCodeDao.insert(po);
        } else {
            promoCodeDao.update(po);
        }
        return promoCodeMapper.toEntity(po);
    }

    @Override
    public void delete(PromoCodeEntity entity) {
        if (entity.getId() != null) {
            PromoCodePO po = promoCodeDao.selectById(entity.getId());
            if (po != null) {
                po.setDeletedAt(Instant.now());
                promoCodeDao.update(po);
            }
        }
    }
}
