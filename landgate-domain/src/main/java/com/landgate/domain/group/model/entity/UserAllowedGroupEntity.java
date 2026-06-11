package com.landgate.domain.group.model.entity;

import lombok.*;

import java.time.Instant;

/**
 * 用户-分组授权实体 —— 对应数据库 user_allowed_groups 表。
 * <p>
 * 记录用户对分组的访问授权关系。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class UserAllowedGroupEntity {

    /** 用户 ID */
    private Long userId;

    /** 分组 ID */
    private Long groupId;

    private Instant createdAt;
}
