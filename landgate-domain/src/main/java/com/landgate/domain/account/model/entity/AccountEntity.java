package com.landgate.domain.account.model.entity;

import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.enums.Status;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 上游 AI 平台账号实体 —— 对应数据库 accounts 表。
 * <p>
 * 每个账号封装一个上游 AI 平台（如 OpenAI、Anthropic）的 API 凭证，
 * 网关通过账号将请求转发到对应的上游服务。支持并发控制、优先级调度、
 * 费率倍数、负载因子和过期自动暂停等运行时管理功能。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class AccountEntity {

    private Long id;

    /** 账号名称 */
    private String name;

    /** 备注说明 */
    private String notes;

    /** 所属平台（openai、anthropic、gemini 等） */
    private Platform platform;

    /** 认证类型（api_key、oauth 等） */
    private AccountType type;

    /** API 凭证（加密存储的 JSON 字符串） */
    private String credentials;

    /** 扩展配置（JSON 格式，含调度参数等） */
    private String extra;

    /** 关联的代理配置 ID */
    private Long proxyId;

    /** 最大并发数，默认 3 */
    @Builder.Default
    private Integer concurrency = 3;

    /** 负载因子（动态调整调度权重） */
    private Integer loadFactor;

    @Builder.Default
    private Integer priority = 50;

    @Builder.Default
    private BigDecimal rateMultiplier = BigDecimal.ONE;

    @Builder.Default
    private Status status = Status.ACTIVE;

    private String errorMessage;
    private Instant lastUsedAt;
    private Instant expiresAt;

    @Builder.Default
    private Boolean autoPauseOnExpired = true;

    @Builder.Default
    private Boolean schedulable = true;

    private Instant rateLimitedAt;
    private Instant rateLimitResetAt;
    private Instant overloadUntil;

    private Instant tempUnschedulableUntil;
    private String tempUnschedulableReason;

    private Instant sessionWindowStart;
    private Instant sessionWindowEnd;
    private String sessionWindowStatus;

    /** 号支持的模型白名单（JSON 数组），NULL 或空表示不限制 */
    private String supportedModels;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    public boolean isActive() { return Status.ACTIVE == status; }
    public boolean isSchedulable() {
        if (!isActive() || !schedulable) return false;
        if (tempUnschedulableUntil != null && tempUnschedulableUntil.isAfter(Instant.now())) return false;
        return true;
    }
    public boolean isRateLimited() { return rateLimitResetAt != null && rateLimitResetAt.isAfter(Instant.now()); }
    public boolean isOverloaded() { return overloadUntil != null && overloadUntil.isAfter(Instant.now()); }
}
