package com.landgate.infrastructure.dao.po;

import com.landgate.types.enums.Status;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * API Key 持久化对象 —— 对应 <code>api_keys</code> 表。
 * <p>
 * 用户创建的 API 访问密钥，用于 API 调用的身份认证和配额控制。
 * 支持软删除。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ApiKeyPO extends BasePO {

    /** 主键，自增 */
    private Long id;

    /** 所属用户 ID */
    private Long userId;

    /** API Key 值（sk-xxx 格式） */
    private String key;

    /** Key 名称（用户自定义） */
    private String name;

    /** 关联分组 ID */
    private Long groupId;

    /** 状态 */
    @Builder.Default
    private Status status = Status.ACTIVE;

    /** 最后使用时间 */
    private Instant lastUsedAt;

    /** IP 白名单（JSON 数组） */
    private String ipWhitelist;

    /** IP 黑名单（JSON 数组） */
    private String ipBlacklist;

    /** 配额上限（USD） */
    @Builder.Default
    private BigDecimal quota = BigDecimal.ZERO;

    /** 已用配额（USD） */
    @Builder.Default
    private BigDecimal quotaUsed = BigDecimal.ZERO;

    /** Key 过期时间 */
    private Instant expiresAt;

    /** 5 小时限流上限 */
    @Builder.Default
    private BigDecimal rateLimit5h = BigDecimal.ZERO;

    /** 5 小时内已使用 */
    @Builder.Default
    private BigDecimal usage5h = BigDecimal.ZERO;

    /** 5 小时窗口起始 */
    private Instant window5hStart;

    /** 1 天限流上限 */
    @Builder.Default
    private BigDecimal rateLimit1d = BigDecimal.ZERO;

    /** 1 天内已使用 */
    @Builder.Default
    private BigDecimal usage1d = BigDecimal.ZERO;

    /** 1 天窗口起始 */
    private Instant window1dStart;

    /** 7 天限流上限 */
    @Builder.Default
    private BigDecimal rateLimit7d = BigDecimal.ZERO;

    /** 7 天内已使用 */
    @Builder.Default
    private BigDecimal usage7d = BigDecimal.ZERO;

    /** 7 天窗口起始 */
    private Instant window7dStart;

    /** 判断 Key 是否处于激活状态 */
    public boolean isActive() { return Status.ACTIVE == status; }
}
