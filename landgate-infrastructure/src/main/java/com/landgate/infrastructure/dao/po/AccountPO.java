package com.landgate.infrastructure.dao.po;

import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.enums.Status;
import lombok.*;

import java.time.Instant;

/**
 * AI 账号持久化对象 —— 对应 <code>accounts</code> 表。
 * <p>
 * 记录上游 AI 服务账号的连接信息、凭证、调度状态等。
 * 支持软删除。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AccountPO extends BasePO {

    /** 主键，自增 */
    private Long id;

    /** 账号名称（用于识别） */
    private String name;

    /** 备注 */
    private String notes;

    /** 所属 AI 平台 */
    private Platform platform;

    /** 认证类型 */
    private AccountType type;

    /** 凭证（JSON，加密存储） */
    private String credentials;

    /** 额外配置（JSON） */
    private String extra;

    /** 关联的代理 ID */
    private Long proxyId;

    /** 并发请求数上限 */
    @Builder.Default
    private Integer concurrency = 3;

    /** 负载因子（加权调度用） */
    private Integer loadFactor;

    /** 调度优先级（数字越小越优先） */
    @Builder.Default
    private Integer priority = 50;

    /** 账号状态 */
    @Builder.Default
    private Status status = Status.ACTIVE;

    /** 错误信息（异常时记录） */
    private String errorMessage;

    /** 最后使用时间 */
    private Instant lastUsedAt;

    /** 账号过期时间 */
    private Instant expiresAt;

    /** 过期后是否自动暂停 */
    @Builder.Default
    private Boolean autoPauseOnExpired = true;

    /** 是否可调度 */
    @Builder.Default
    private Boolean schedulable = true;

    /** 被限流的时间 */
    private Instant rateLimitedAt;

    /** 限流重置时间 */
    private Instant rateLimitResetAt;

    /** 过载截止时间 */
    private Instant overloadUntil;

    /** 临时不可调度截止时间 */
    private Instant tempUnschedulableUntil;

    /** 临时不可调度原因 */
    private String tempUnschedulableReason;

    /** 会话窗口开始时间 */
    private Instant sessionWindowStart;

    /** 会话窗口结束时间 */
    private Instant sessionWindowEnd;

    /** 会话窗口状态 */
    private String sessionWindowStatus;

    /** 号支持的模型白名单（JSON 数组），NULL 或空表示不限制 */
    private String supportedModels;

    /** 账户支持的上游 API 协议（JSON 数组），如 ["chat_completions","responses"]。NULL = 默认格式 */
    private String supportedProtocols;

    /** 允许跨 Provider 混合调度（如 Antigravity 账号混入 Anthropic 号池） */
    @Builder.Default
    private Boolean mixedScheduling = false;

    /** 判断账号是否处于激活状态 */
    public boolean isActive() { return Status.ACTIVE == status; }

    /** 判断账号是否可被调度（激活且未禁用调度） */
    public boolean canBeScheduled() { return Boolean.TRUE.equals(schedulable) && Status.ACTIVE == status; }
}
