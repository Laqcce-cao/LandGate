package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.marketing.adapter.repository.IRedeemCodeRepository;
import com.landgate.domain.marketing.model.entity.RedeemCodeEntity;
import com.landgate.infrastructure.adapter.mapper.RedeemCodeMapper;
import com.landgate.infrastructure.dao.IRedeemCodeDao;
import com.landgate.infrastructure.dao.po.RedeemCodePO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 兑换码仓储适配器实现 —— 实现 {@link IRedeemCodeRepository} 接口。
 * <p>
 * 委托 {@link IRedeemCodeDao} 进行数据访问，通过 {@link RedeemCodeMapper} 完成 PO ↔ Entity 映射。
 * 使用软删除策略。
 */
@Component
@RequiredArgsConstructor
public class RedeemCodeRepositoryImpl implements IRedeemCodeRepository {

    private final IRedeemCodeDao redeemCodeDao;
    private final RedeemCodeMapper redeemCodeMapper;

    @Override
    public Optional<RedeemCodeEntity> findById(Long id) {
        return Optional.ofNullable(redeemCodeDao.selectById(id))
                .filter(po -> po.getDeletedAt() == null)
                .map(redeemCodeMapper::toEntity);
    }

    @Override
    public Optional<RedeemCodeEntity> findByCode(String code) {
        return Optional.ofNullable(redeemCodeDao.selectByCode(code))
                .filter(po -> po.getDeletedAt() == null)
                .map(redeemCodeMapper::toEntity);
    }

    @Override
    public List<RedeemCodeEntity> findAll() {
        return redeemCodeDao.selectAll().stream()
                .map(redeemCodeMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<RedeemCodeEntity> findByEnabledTrue() {
        return redeemCodeDao.selectEnabled().stream()
                .map(redeemCodeMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<RedeemCodeEntity> findByCreatedBy(Long createdBy) {
        return redeemCodeDao.selectByCreatedBy(createdBy).stream()
                .map(redeemCodeMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public RedeemCodeEntity save(RedeemCodeEntity entity) {
        RedeemCodePO po = redeemCodeMapper.toPO(entity);
        if (po.getId() == null) {
            redeemCodeDao.insert(po);
        } else {
            redeemCodeDao.update(po);
        }
        return redeemCodeMapper.toEntity(po);
    }

    @Override
    public void delete(RedeemCodeEntity entity) {
        if (entity.getId() != null) {
            RedeemCodePO po = redeemCodeDao.selectById(entity.getId());
            if (po != null) {
                po.setDeletedAt(Instant.now());
                redeemCodeDao.update(po);
            }
        }
    }
}
