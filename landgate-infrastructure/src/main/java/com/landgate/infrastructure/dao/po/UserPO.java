package com.landgate.infrastructure.dao.po;

import com.landgate.types.enums.Role;
import com.landgate.types.enums.SignupSource;
import com.landgate.types.enums.Status;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 用户持久化对象 —— 对应 <code>users</code> 表。
 * <p>
 * 核心表，记录所有注册用户的信息、余额、偏好设置等。
 * 支持软删除。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class UserPO extends BasePO {

    /** 主键，自增 */
    private Long id;

    /** 邮箱地址，唯一标识 */
    private String email;

    /** bcrypt 密码哈希 */
    private String passwordHash;

    /** 用户角色（管理员/普通用户） */
    @Builder.Default
    private Role role = Role.USER;

    /** 账户余额（USD） */
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    /** 并发请求数上限 */
    @Builder.Default
    private Integer concurrency = 5;

    /** 账户状态 */
    @Builder.Default
    private Status status = Status.ACTIVE;

    /** 用户名/昵称 */
    @Builder.Default
    private String username = "";

    /** 管理员备注 */
    private String notes;

    /** TOTP 密钥（加密存储） */
    private String totpSecretEncrypted;

    /** TOTP 是否已启用 */
    @Builder.Default
    private Boolean totpEnabled = false;

    /** TOTP 启用时间 */
    private Instant totpEnabledAt;

    /** 注册来源 */
    @Builder.Default
    private SignupSource signupSource = SignupSource.EMAIL;

    /** 最后登录时间 */
    private Instant lastLoginAt;

    /** 最后活跃时间 */
    private Instant lastActiveAt;

    /** 余额通知是否已启用 */
    @Builder.Default
    private Boolean balanceNotifyEnabled = true;

    /** 余额通知阈值类型（fixed/percentage） */
    @Builder.Default
    private String balanceNotifyThresholdType = "fixed";

    /** 余额通知阈值 */
    private BigDecimal balanceNotifyThreshold;

    /** 余额通知额外邮箱（JSON 数组） */
    private String balanceNotifyExtraEmails;

    /** 累计充值金额（USD） */
    @Builder.Default
    private BigDecimal totalRecharged = BigDecimal.ZERO;

    /** Token 版本号（递增以强制所有 JWT 失效） */
    @Builder.Default
    private Long tokenVersion = 0L;

    /** 每分钟请求数上限（0 = 不限制） */
    @Builder.Default
    private Integer rpmLimit = 0;

    /** 判断是否为管理员 */
    public boolean isAdmin() { return Role.ADMIN == role; }

    /** 判断账户是否处于激活状态 */
    public boolean isActive() { return Status.ACTIVE == status; }
}
