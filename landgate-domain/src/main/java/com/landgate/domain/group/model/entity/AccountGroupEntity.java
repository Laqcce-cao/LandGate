package com.landgate.domain.group.model.entity;

import lombok.*;

import java.time.Instant;

/**
 * 账号-分组关联实体 —— 对应数据库 account_groups 或多对多关联表。
 * <p>
 * 记录上游账号与分组的绑定关系，包含调度优先级。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class AccountGroupEntity {

    /** 上游账号 ID */
    private Long accountId;

    /** 分组 ID */
    private Long groupId;

    /** 调度优先级（数值越小越优先） */
    @Builder.Default
    private Integer priority = 50;

    private Instant createdAt;
}
