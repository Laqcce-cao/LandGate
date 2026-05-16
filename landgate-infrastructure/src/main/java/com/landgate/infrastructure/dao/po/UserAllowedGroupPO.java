package com.landgate.infrastructure.dao.po;

import lombok.*;

import java.time.Instant;

/**
 * 用户-分组关联持久化对象 —— 对应 <code>user_allowed_groups</code> 表。
 * <p>
 * 多对多关联表，控制用户对分组的访问权限。
 * 使用复合主键 (user_id, group_id)，硬删除。
 * 不继承 BasePO（独立管理 createdAt）。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class UserAllowedGroupPO {

    /** 用户 ID（复合主键之一） */
    private Long userId;

    /** 分组 ID（复合主键之一） */
    private Long groupId;

    /** 创建时间 */
    private Instant createdAt;
}
