package com.landgate.domain.marketing.service;

import com.landgate.domain.marketing.adapter.repository.IPromoCodeRepository;
import com.landgate.domain.marketing.model.entity.PromoCodeEntity;
import com.landgate.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 优惠码领域服务 —— 优惠码校验与折扣计算。
 * <p>
 * 校验优惠码的有效性（是否过期、是否用完、是否已开始），
 * 并根据折扣类型计算折后金额。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromoDomainService {

    private final IPromoCodeRepository promoCodeRepository;

    /**
     * 校验优惠码并计算折后金额。
     *
     * @param code           优惠码
     * @param originalAmount 原始金额
     * @return 包含校验结果和折后金额的 Map
     * @throws BusinessException 优惠码无效、过期、已用完或未开始激活时抛出
     */
    public Map<String, Object> validateAndCalculate(String code, BigDecimal originalAmount) {
        PromoCodeEntity promoCode = promoCodeRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException("PROMO_NOT_FOUND", "Promo code not found: " + code));

        if (!promoCode.isUsable()) {
            if (promoCode.isExpired()) throw new BusinessException("PROMO_EXPIRED", "Promo code has expired");
            if (!promoCode.hasRemainingUses()) throw new BusinessException("PROMO_EXHAUSTED", "Promo code has been fully used");
            if (!promoCode.hasStarted()) throw new BusinessException("PROMO_NOT_ACTIVE", "Promo code is not yet active");
            throw new BusinessException("PROMO_UNAVAILABLE", "Promo code is not available");
        }

        BigDecimal discountedAmount = promoCode.applyDiscount(originalAmount);
        BigDecimal savedAmount = originalAmount.subtract(discountedAmount);

        log.info("Promo code applied: code={}, original={}, discounted={}, saved={}", code, originalAmount, discountedAmount, savedAmount);

        return Map.of("valid", true, "code", code, "discount_type", promoCode.getDiscountType(),
                "discount_value", promoCode.getDiscountValue(), "original_amount", originalAmount,
                "discounted_amount", discountedAmount, "saved_amount", savedAmount);
    }
}
