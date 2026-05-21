package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.auth.adapter.repository.IApiKeyRepository;
import com.landgate.domain.auth.model.entity.ApiKeyEntity;
import com.landgate.infrastructure.adapter.mapper.ApiKeyMapper;
import com.landgate.infrastructure.dao.IApiKeyDao;
import com.landgate.infrastructure.dao.po.ApiKeyPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * API Key 仓储适配器实现 —— 实现 {@link IApiKeyRepository} 接口。
 * <p>
 * 委托 {@link IApiKeyDao} 进行数据访问，通过 {@link ApiKeyMapper} 完成 PO ↔ Entity 映射。
 * 使用软删除策略。
 */
@Component
@RequiredArgsConstructor
public class ApiKeyRepositoryImpl implements IApiKeyRepository {

    private final IApiKeyDao apiKeyDao;
    private final ApiKeyMapper apiKeyMapper;

    @Override
    public Optional<ApiKeyEntity> findByKey(String key) {
        return Optional.ofNullable(apiKeyDao.selectByKey(key))
                .map(apiKeyMapper::toEntity);
    }

    @Override
    public List<ApiKeyEntity> findByUserId(Long userId) {
        return apiKeyDao.selectByUserId(userId).stream()
                .map(apiKeyMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public ApiKeyEntity save(ApiKeyEntity entity) {
        ApiKeyPO po = apiKeyMapper.toPO(entity);
        if (po.getId() == null) {
            apiKeyDao.insert(po);
        } else {
            apiKeyDao.update(po);
        }
        return apiKeyMapper.toEntity(po);
    }

    @Override
    public Optional<ApiKeyEntity> findById(Long id) {
        return Optional.ofNullable(apiKeyDao.selectById(id))
                .map(apiKeyMapper::toEntity);
    }

    @Override
    public void deleteById(Long id) {
        ApiKeyPO po = apiKeyDao.selectById(id);
        if (po != null) {
            po.setDeletedAt(Instant.now());
            apiKeyDao.update(po);
        }
    }
}
