package com.landgate.infrastructure.dao.po;

import lombok.*;

import java.time.Instant;

/**
 * 账号-分组关联持久化对象 —— 对应 <code>account_groups</code> 表。
 * <p>
 * 多对多关联表，将上游账号绑定到分组并设置调度优先级。
 * 使用复合主键 (account_id, group_id)，硬删除（不经过软删除）。
 * 不继承 BasePO（独立管理 createdAt）。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AccountGroupPO {

    /** 账号 ID（复合主键之一） */
    private Long accountId;

    /** 分组 ID（复合主键之一） */
    private Long groupId;

    /** 调度优先级（数字越小越优先） */
    @Builder.Default
    private Integer priority = 50;

    /** 创建时间 */
    private Instant createdAt;
}
