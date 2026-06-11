package com.landgate.domain.marketing.model.entity;

import com.landgate.types.enums.DiscountType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 优惠码实体 —— 对应数据库 promo_codes 表。
 * <p>
 * 支持固定金额和百分比两种折扣类型，可设最低订单金额、最高折扣上限、
 * 使用次数限制和有效期。用于支付时减免金额。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class PromoCodeEntity {

    private Long id;

    /** 优惠码字符串 */
    private String code;

    /** 折扣类型（FIXED / PERCENTAGE） */
    @Builder.Default
    private DiscountType discountType = DiscountType.FIXED;

    /** 折扣值（金额或百分比） */
    private BigDecimal discountValue;

    /** 最低订单金额门槛 */
    @Builder.Default
    private BigDecimal minOrderAmount = BigDecimal.ZERO;

    /** 最高折扣金额上限 */
    @Builder.Default
    private BigDecimal maxDiscountAmount = BigDecimal.ZERO;

    /** 最大使用次数（0 = 不限） */
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

    private Instant startsAt;
    private Instant expiresAt;
    private Long createdBy;
    private String notes;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    public boolean isDeleted() { return deletedAt != null; }
    public boolean isPercentage() { return DiscountType.PERCENTAGE == discountType; }
    public boolean isFixed() { return DiscountType.FIXED == discountType; }
    public boolean hasRemainingUses() { return maxUses == 0 || usedCount < maxUses; }
    public boolean isExpired() { return expiresAt != null && expiresAt.isBefore(Instant.now()); }
    public boolean hasStarted() { return startsAt == null || startsAt.isBefore(Instant.now()); }
    public boolean isUsable() { return enabled && !isDeleted() && hasRemainingUses() && !isExpired() && hasStarted(); }

    public BigDecimal applyDiscount(BigDecimal originalAmount) {
        if (originalAmount == null || originalAmount.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        if (minOrderAmount.compareTo(BigDecimal.ZERO) > 0 && originalAmount.compareTo(minOrderAmount) < 0) return originalAmount;
        BigDecimal discount = isPercentage()
                ? originalAmount.multiply(discountValue).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP)
                : discountValue;
        if (maxDiscountAmount.compareTo(BigDecimal.ZERO) > 0 && discount.compareTo(maxDiscountAmount) > 0) discount = maxDiscountAmount;
        BigDecimal result = originalAmount.subtract(discount);
        return result.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : result;
    }
}
