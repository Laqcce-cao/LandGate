package com.landgate.infrastructure.dao.po;

import com.landgate.types.enums.RedeemCodeType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 兑换码持久化对象 —— 对应 <code>redeem_codes</code> 表。
 * <p>
 * 管理员生成的兑换码，用户兑换后可获得余额或订阅时长。
 * 支持软删除。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RedeemCodePO extends BasePO {

    /** 主键，自增 */
    private Long id;

    /** 兑换码（唯一） */
    private String code;

    /** 兑换类型（余额/订阅） */
    @Builder.Default
    private RedeemCodeType type = RedeemCodeType.BALANCE;

    /** 兑换金额（USD，余额类型时有效） */
    private BigDecimal amount;

    /** 关联分组 ID（订阅类型时可指定） */
    private Long groupId;

    /** 订阅天数（订阅类型时有效） */
    private Integer subscriptionDays;

    /** 最大使用次数（0 = 无限） */
    @Builder.Default
    private Integer maxUses = 1;

    /** 已使用次数 */
    @Builder.Default
    private Integer usedCount = 0;

    /** 绑定用户 ID（为空则任意用户可用） */
    private Long boundUserId;

    /** 是否启用 */
    @Builder.Default
    private Boolean enabled = true;

    /** 过期时间 */
    private Instant expiresAt;

    /** 创建人 ID */
    private Long createdBy;

    /** 备注 */
    @Builder.Default
    private String notes = "";

    public boolean isBalance() { return RedeemCodeType.BALANCE == type; }
    public boolean isSubscription() { return RedeemCodeType.SUBSCRIPTION == type; }
    public boolean hasRemainingUses() { return maxUses == 0 || usedCount < maxUses; }
    public boolean isExpired() { return expiresAt != null && expiresAt.isBefore(Instant.now()); }
    public boolean isRedeemable() {
        return enabled && !isDeleted() && hasRemainingUses() && !isExpired();
    }
}
