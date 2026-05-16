package com.landgate.domain.marketing.model.entity;

import com.landgate.types.enums.AnnouncementType;
import lombok.*;

import java.time.Instant;

/**
 * 公告实体 —— 对应数据库 announcements 表。
 * <p>
 * 管理系统公告的标题、内容、类型、发布时间、过期时间和发布状态。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class AnnouncementEntity {

    private Long id;

    /** 公告标题 */
    private String title;

    /** 公告内容 */
    private String content;

    /** 公告类型（INFO、WARNING 等） */
    @Builder.Default
    private AnnouncementType type = AnnouncementType.INFO;

    /** 是否已发布 */
    @Builder.Default
    private Boolean published = false;

    /** 发布时间 */
    private Instant publishAt;

    /** 过期时间 */
    private Instant expiresAt;

    /** 排序权重 */
    @Builder.Default
    private Integer sortOrder = 0;

    /** 创建者 ID */
    private Long createdBy;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
}
