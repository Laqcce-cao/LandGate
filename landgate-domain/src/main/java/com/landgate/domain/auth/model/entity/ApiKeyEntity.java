package com.landgate.domain.auth.model.entity;

import com.landgate.types.enums.Status;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * API 密钥实体 —— 对应数据库 api_keys 表。
 * <p>
 * 每个 API Key 由用户创建，网关通过 API Key 鉴权请求。
 * 支持分组关联、IP 黑白名单、额度限制和多时间窗口的速率控制。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class ApiKeyEntity {

    private Long id;

    /** 所属用户 ID */
    private Long userId;

    /** 密钥字符串（ak- 开头） */
    private String key;

    /** 密钥名称 */
    private String name;

    /** 关联的分组 ID */
    private Long groupId;

    /** 密钥状态 */
    @Builder.Default
    private Status status = Status.ACTIVE;

    /** 最近使用时间 */
    private Instant lastUsedAt;

    /** IP 白名单 */
    private String ipWhitelist;

    /** IP 黑名单 */
    private String ipBlacklist;

    /** 额度上限 */
    @Builder.Default
    private BigDecimal quota = BigDecimal.ZERO;

    /** 已使用额度 */
    @Builder.Default
    private BigDecimal quotaUsed = BigDecimal.ZERO;

    /** 过期时间 */
    private Instant expiresAt;

    /** 5 小时内限额 */
    @Builder.Default
    private BigDecimal rateLimit5h = BigDecimal.ZERO;
    /** 5 小时内已使用 */
    @Builder.Default
    private BigDecimal usage5h = BigDecimal.ZERO;
    private Instant window5hStart;

    /** 1 天内限额 */
    @Builder.Default
    private BigDecimal rateLimit1d = BigDecimal.ZERO;
    /** 1 天内已使用 */
    @Builder.Default
    private BigDecimal usage1d = BigDecimal.ZERO;
    private Instant window1dStart;

    /** 7 天内限额 */
    @Builder.Default
    private BigDecimal rateLimit7d = BigDecimal.ZERO;
    /** 7 天内已使用 */
    @Builder.Default
    private BigDecimal usage7d = BigDecimal.ZERO;
    private Instant window7dStart;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    public boolean isActive() { return Status.ACTIVE == status; }
}
