package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.billing.adapter.repository.IModelPriceRepository;
import com.landgate.domain.billing.model.entity.ModelPriceEntity;
import com.landgate.infrastructure.adapter.mapper.ModelPriceMapper;
import com.landgate.infrastructure.dao.IModelPriceDao;
import com.landgate.infrastructure.dao.po.ModelPricePO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 模型价格仓储适配器实现 —— 实现 {@link IModelPriceRepository} 接口。
 * <p>
 * 委托 {@link IModelPriceDao} 进行数据访问，通过 {@link ModelPriceMapper} 完成 PO ↔ Entity 映射。
 * 支持分组覆盖查询和软删除。
 */
@Component
@RequiredArgsConstructor
public class ModelPriceRepositoryImpl implements IModelPriceRepository {

    private final IModelPriceDao modelPriceDao;
    private final ModelPriceMapper modelPriceMapper;

    @Override
    public Optional<ModelPriceEntity> findById(Long id) {
        return Optional.ofNullable(modelPriceDao.selectById(id))
                .map(modelPriceMapper::toEntity);
    }

    @Override
    public Optional<ModelPriceEntity> findByModelAndGroup(String model, Long groupId) {
        return Optional.ofNullable(modelPriceDao.selectByModelAndGroup(model, groupId))
                .map(modelPriceMapper::toEntity);
    }

    @Override
    public List<ModelPriceEntity> findByPlatform(String platform) {
        return modelPriceDao.selectByPlatform(platform).stream()
                .map(modelPriceMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<ModelPriceEntity> findAll(int page, int size) {
        int offset = page * size;
        return modelPriceDao.selectAll(offset, size).stream()
                .map(modelPriceMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public long count() {
        return modelPriceDao.countAll();
    }

    @Override
    public ModelPriceEntity save(ModelPriceEntity entity) {
        ModelPricePO po = modelPriceMapper.toPO(entity);
        if (po.getId() == null) {
            modelPriceDao.insert(po);
        } else {
            modelPriceDao.update(po);
        }
        return modelPriceMapper.toEntity(po);
    }

    @Override
    public void deleteById(Long id) {
        modelPriceDao.softDelete(id);
    }
}
