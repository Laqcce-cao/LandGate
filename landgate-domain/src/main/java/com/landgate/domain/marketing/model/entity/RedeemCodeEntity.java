package com.landgate.domain.marketing.model.entity;

import com.landgate.types.enums.RedeemCodeType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 兑换码实体 —— 对应数据库 redeem_codes 表。
 * <p>
 * 支持余额充值（直接增加余额）和订阅开通（授予分组访问权限）两种类型。
 * 可绑定指定用户，设置使用次数限制和有效期。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class RedeemCodeEntity {

    private Long id;

    /** 兑换码字符串 */
    private String code;

    /** 兑换类型（BALANCE / SUBSCRIPTION） */
    @Builder.Default
    private RedeemCodeType type = RedeemCodeType.BALANCE;

    /** 充值金额（余额类型） */
    private BigDecimal amount;

    /** 订阅分组 ID（订阅类型） */
    private Long groupId;

    /** 订阅天数（订阅类型） */
    private Integer subscriptionDays;

    /** 最大使用次数 */
    @Builder.Default
    private Integer maxUses = 1;

    /** 已使用次数 */
    @Builder.Default
    private Integer usedCount = 0;

    /** 绑定用户 ID（null 表示不绑定） */
    private Long boundUserId;

    /** 是否启用 */
    @Builder.Default
    private Boolean enabled = true;

    private Instant expiresAt;
    private Long createdBy;
    private String notes;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    public boolean isDeleted() { return deletedAt != null; }
    public boolean isBalance() { return RedeemCodeType.BALANCE == type; }
    public boolean isSubscription() { return RedeemCodeType.SUBSCRIPTION == type; }
    public boolean hasRemainingUses() { return maxUses == 0 || usedCount < maxUses; }
    public boolean isExpired() { return expiresAt != null && expiresAt.isBefore(Instant.now()); }
    public boolean isRedeemable() { return enabled && !isDeleted() && hasRemainingUses() && !isExpired(); }
}
