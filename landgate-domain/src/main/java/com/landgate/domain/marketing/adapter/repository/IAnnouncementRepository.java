package com.landgate.domain.marketing.adapter.repository;

import com.landgate.domain.marketing.model.entity.AnnouncementEntity;

import java.util.List;
import java.util.Optional;

/**
 * 公告（Announcement）仓储接口 —— 定义领域层所需的系统公告持久化操作契约。
 * <p>
 * 由基础设施层的 RepositoryImpl 实现，通过依赖反转实现领域层不依赖基础设施。
 * 管理系统公告的发布与展示，支持查询已发布公告、当前生效公告等操作。
 */
public interface IAnnouncementRepository {

    /**
     * 根据ID查询公告
     *
     * @param id 主键ID
     * @return 查询到的公告，不存在返回 Optional.empty()
     */
    Optional<AnnouncementEntity> findById(Long id);

    /**
     * 查询所有公告
     *
     * @return 全部公告列表
     */
    List<AnnouncementEntity> findAll();

    /**
     * 查询所有已发布的公告
     *
     * @return 已发布公告列表
     */
    List<AnnouncementEntity> findByPublishedTrue();

    /**
     * 查询当前生效的公告
     *
     * @return 当前生效的公告列表
     */
    List<AnnouncementEntity> findActive();

    /**
     * 保存公告（新增或更新）
     *
     * @param entity 公告实体
     * @return 保存后的公告实体
     */
    AnnouncementEntity save(AnnouncementEntity entity);

    /**
     * 删除公告
     *
     * @param entity 待删除的公告实体
     */
    void delete(AnnouncementEntity entity);
}
