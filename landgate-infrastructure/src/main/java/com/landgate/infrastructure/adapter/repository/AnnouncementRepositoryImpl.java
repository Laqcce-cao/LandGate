package com.landgate.infrastructure.adapter.repository;

import com.landgate.domain.marketing.adapter.repository.IAnnouncementRepository;
import com.landgate.domain.marketing.model.entity.AnnouncementEntity;
import com.landgate.infrastructure.adapter.mapper.AnnouncementMapper;
import com.landgate.infrastructure.dao.IAnnouncementDao;
import com.landgate.infrastructure.dao.po.AnnouncementPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 公告仓储适配器实现 —— 实现 {@link IAnnouncementRepository} 接口。
 * <p>
 * 委托 {@link IAnnouncementDao} 进行数据访问，通过 {@link AnnouncementMapper} 完成 PO ↔ Entity 映射。
 * 使用软删除策略。
 */
@Component
@RequiredArgsConstructor
public class AnnouncementRepositoryImpl implements IAnnouncementRepository {

    private final IAnnouncementDao announcementDao;
    private final AnnouncementMapper announcementMapper;

    @Override
    public Optional<AnnouncementEntity> findById(Long id) {
        return Optional.ofNullable(announcementDao.selectById(id))
                .filter(po -> po.getDeletedAt() == null)
                .map(announcementMapper::toEntity);
    }

    @Override
    public List<AnnouncementEntity> findAll() {
        return announcementDao.selectAll().stream()
                .map(announcementMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<AnnouncementEntity> findByPublishedTrue() {
        return announcementDao.selectPublished().stream()
                .map(announcementMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<AnnouncementEntity> findActive() {
        return announcementDao.selectActive().stream()
                .map(announcementMapper::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public AnnouncementEntity save(AnnouncementEntity entity) {
        AnnouncementPO po = announcementMapper.toPO(entity);
        if (po.getId() == null) {
            announcementDao.insert(po);
        } else {
            announcementDao.update(po);
        }
        return announcementMapper.toEntity(po);
    }

    @Override
    public void delete(AnnouncementEntity entity) {
        if (entity.getId() != null) {
            AnnouncementPO po = announcementDao.selectById(entity.getId());
            if (po != null) {
                po.setDeletedAt(Instant.now());
                announcementDao.update(po);
            }
        }
    }
}
