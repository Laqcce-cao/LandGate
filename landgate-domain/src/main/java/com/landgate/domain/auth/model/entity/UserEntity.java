package com.landgate.domain.auth.model.entity;

import com.landgate.types.enums.Role;
import com.landgate.types.enums.SignupSource;
import com.landgate.types.enums.Status;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 用户实体 —— 对应数据库 users 表。
 * <p>
 * 系统用户的核心聚合根，管理用户的身份认证、余额、角色、并发限制、
 * TOTP 二步验证和余额告警通知等配置。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class UserEntity {

    private Long id;

    /** 邮箱（登录凭证） */
    private String email;

    /** 邮箱是否已验证 */
    @Builder.Default
    private Boolean emailVerified = false;

    /** BCrypt 密码哈希 */
    private String passwordHash;

    /** 角色（admin / user） */
    @Builder.Default
    private String role = Role.USER.getKey();

    /** 账户余额 */
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    /** 最大并发数，默认 5 */
    @Builder.Default
    private Integer concurrency = 5;

    /** 账号状态 */
    @Builder.Default
    private String status = Status.ACTIVE.getKey();

    /** 用户名（默认取邮箱 @ 前部分） */
    @Builder.Default
    private String username = "";

    /** 管理员备注 */
    private String notes;

    /** TOTP 密钥（加密存储） */
    private String totpSecretEncrypted;

    /** 是否启用 TOTP */
    @Builder.Default
    private Boolean totpEnabled = false;

    private Instant totpEnabledAt;

    /** 注册来源 */
    @Builder.Default
    private String signupSource = SignupSource.EMAIL.getKey();

    /** 最后登录时间 */
    private Instant lastLoginAt;

    /** 最后活跃时间 */
    private Instant lastActiveAt;

    /** 是否启用余额告警通知 */
    @Builder.Default
    private Boolean balanceNotifyEnabled = true;

    /** 余额告警阈值类型 */
    @Builder.Default
    private String balanceNotifyThresholdType = "fixed";

    /** 余额告警阈值 */
    private BigDecimal balanceNotifyThreshold;

    /** 额外告警通知邮箱 */
    private String balanceNotifyExtraEmails;

    /** 累计充值金额 */
    @Builder.Default
    private BigDecimal totalRecharged = BigDecimal.ZERO;

    /** Token 版本号（递增可吊销所有已签发 Token） */
    @Builder.Default
    private Long tokenVersion = 0L;

    /** 每分钟请求数限制 */
    @Builder.Default
    private Integer rpmLimit = 0;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    public boolean isAdmin() { return Role.ADMIN.getKey().equals(role); }
    public boolean isBetaTester() { return Role.BETA_TESTER.getKey().equals(role); }
    public boolean isPrivileged() { return isAdmin() || isBetaTester(); }
    public boolean isActive() { return Status.ACTIVE.getKey().equals(status); }
}
