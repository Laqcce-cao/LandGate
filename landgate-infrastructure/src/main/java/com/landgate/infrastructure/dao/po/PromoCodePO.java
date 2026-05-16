package com.landgate.infrastructure.dao.po;

import com.landgate.types.enums.DiscountType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 优惠码持久化对象 —— 对应 <code>promo_codes</code> 表。
 * <p>
 * 管理员创建的优惠码，用户在支付时可输入以获得折扣。
 * 支持软删除。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PromoCodePO extends BasePO {

    /** 主键，自增 */
    private Long id;

    /** 优惠码（唯一） */
    private String code;

    /** 折扣类型（百分比/固定金额） */
    @Builder.Default
    private DiscountType discountType = DiscountType.FIXED;

    /** 折扣值（百分比则为百分比值，固定则为金额） */
    private BigDecimal discountValue;

    /** 最低订单金额（USD，低于此金额不生效） */
    @Builder.Default
    private BigDecimal minOrderAmount = BigDecimal.ZERO;

    /** 最大折扣金额（USD，百分比折扣时限制上限） */
    @Builder.Default
    private BigDecimal maxDiscountAmount = BigDecimal.ZERO;

    /** 最大使用次数（0 = 无限） */
    @Builder.Default
    private Integer maxUses = 0;

    /** 已使用次数 */
    @Builder.Default
    private Integer usedCount = 0;

    /** 每用户最大使用次数 */
    @Builder.Default
    private Integer maxUsesPerUser = 1;

    /** 是否启用 */
    @Builder.Default
    private Boolean enabled = true;

    /** 生效开始时间 */
    private Instant startsAt;

    /** 过期时间 */
    private Instant expiresAt;

    /** 创建人 ID */
    private Long createdBy;

    /** 备注 */
    @Builder.Default
    private String notes = "";

    public boolean isPercentage() { return DiscountType.PERCENTAGE == discountType; }
    public boolean isFixed() { return DiscountType.FIXED == discountType; }
    public boolean hasRemainingUses() { return maxUses == 0 || usedCount < maxUses; }
    public boolean isExpired() { return expiresAt != null && expiresAt.isBefore(Instant.now()); }
    public boolean hasStarted() { return startsAt == null || startsAt.isBefore(Instant.now()); }
    public boolean isUsable() {
        return enabled && !isDeleted() && hasRemainingUses() && !isExpired() && hasStarted();
    }
}
