package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.account.adapter.repository.IProxyRepository;
import com.landgate.domain.account.model.entity.ProxyEntity;
import com.landgate.infrastructure.adapter.mapper.ProxyMapper;
import com.landgate.infrastructure.dao.IProxyDao;
import com.landgate.infrastructure.dao.po.ProxyPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 代理仓储适配器实现 —— 实现 {@link IProxyRepository} 接口。
 * <p>
 * 委托 {@link IProxyDao} 进行数据访问，通过 {@link ProxyMapper} 完成 PO ↔ Entity 映射。
 * 使用软删除策略。
 */
@Component
@RequiredArgsConstructor
public class ProxyRepositoryImpl implements IProxyRepository {

    private final IProxyDao proxyDao;
    private final ProxyMapper proxyMapper;

    @Override
    public Optional<ProxyEntity> findById(Long id) {
        return Optional.ofNullable(proxyDao.selectById(id))
                .filter(po -> po.getDeletedAt() == null)
                .map(proxyMapper::toEntity);
    }

    @Override
    public ProxyEntity save(ProxyEntity entity) {
        ProxyPO po = proxyMapper.toPO(entity);
        if (po.getId() == null) {
            proxyDao.insert(po);
        } else {
            proxyDao.update(po);
        }
        return proxyMapper.toEntity(po);
    }

    @Override
    public List<ProxyEntity> findAll() {
        return proxyDao.selectAll().stream()
                .map(proxyMapper::toEntity)
                .collect(Collectors.toList());
    }
}
