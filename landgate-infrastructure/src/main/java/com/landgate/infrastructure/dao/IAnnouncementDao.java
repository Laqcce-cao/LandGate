package com.landgate.infrastructure.dao;

import com.landgate.infrastructure.dao.po.AnnouncementPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 公告 DAO —— MyBatis Mapper 接口。
 * <p>
 * XML 映射文件：mapper/AnnouncementMapper.xml
 * 对应表：announcements（软删除）
 */
@Mapper
public interface IAnnouncementDao {

    /** 根据 ID 查询未删除的公告 */
    AnnouncementPO selectById(@Param("id") Long id);

    /** 插入公告，useGeneratedKeys 回填 ID */
    int insert(AnnouncementPO announcement);

    /** 更新公告所有字段 */
    int update(AnnouncementPO announcement);

    /** 查询所有未删除的公告 */
    List<AnnouncementPO> selectAll();

    /** 统计未删除公告总数 */
    long countAll();

    /** 查询已发布且未删除的公告 */
    List<AnnouncementPO> selectPublished();

    /**
     * 查询当前活跃的公告。
     * 条件：未删除 AND 已发布 AND (发布时间为空或已到达) AND (过期时间为空或未过)
     * 排序：sort_order 升序，created_at 降序
     */
    List<AnnouncementPO> selectActive();
}
