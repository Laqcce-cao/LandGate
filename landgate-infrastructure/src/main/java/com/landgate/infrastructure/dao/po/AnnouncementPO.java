package com.landgate.infrastructure.dao.po;

import com.landgate.types.enums.AnnouncementType;
import lombok.*;

import java.time.Instant;

/**
 * 公告持久化对象 —— 对应 <code>announcements</code> 表。
 * <p>
 * 系统公告，支持定时发布和过期，在前端页面展示。
 * 支持软删除。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AnnouncementPO extends BasePO {

    /** 主键，自增 */
    private Long id;

    /** 公告标题 */
    private String title;

    /** 公告内容（支持 Markdown） */
    private String content;

    /** 公告类型（信息/警告/成功/错误，影响展示样式） */
    @Builder.Default
    private AnnouncementType type = AnnouncementType.INFO;

    /** 是否已发布 */
    @Builder.Default
    private Boolean published = false;

    /** 计划发布时间 */
    private Instant publishAt;

    /** 过期时间 */
    private Instant expiresAt;

    /** 排序序号 */
    @Builder.Default
    private Integer sortOrder = 0;

    /** 创建人 ID */
    private Long createdBy;

    /**
     * 判断公告当前是否可见。
     * 条件：已发布 AND 已到发布时间 AND 未过期。
     */
    public boolean isActive() {
        Instant now = Instant.now();
        boolean notExpired = expiresAt == null || expiresAt.isAfter(now);
        boolean released = publishAt == null || publishAt.isBefore(now);
        return published && released && notExpired;
    }
}
