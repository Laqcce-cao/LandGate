package com.landgate.domain.marketing.service;

import com.landgate.domain.marketing.adapter.repository.IRedeemCodeRepository;
import com.landgate.domain.marketing.model.entity.RedeemCodeEntity;
import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 兑换码领域服务 —— 兑换码校验与余额充值/订阅开通。
 * <p>
 * 支持两种兑换类型：余额充值（直接增加用户余额）和订阅开通（授权分组访问）。
 * 兑换码可指定绑定用户，非绑定用户无法使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedeemDomainService {

    private final IRedeemCodeRepository redeemCodeRepository;
    private final IUserRepository userRepository;

    /**
     * 校验并执行兑换操作。
     *
     * @param code   兑换码
     * @param userId 兑换用户 ID
     * @return 包含兑换类型和结果的 Map
     * @throws BusinessException 兑换码无效、过期、已用完或绑定用户不匹配时抛出
     */
    @Transactional
    public Map<String, Object> redeem(String code, Long userId) {
        RedeemCodeEntity redeemCode = redeemCodeRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException("REDEEM_NOT_FOUND", "Redeem code not found: " + code));

        if (!redeemCode.isRedeemable()) {
            if (redeemCode.isExpired()) throw new BusinessException("REDEEM_EXPIRED", "Redeem code has expired");
            if (!redeemCode.hasRemainingUses()) throw new BusinessException("REDEEM_EXHAUSTED", "Redeem code has been fully used");
            throw new BusinessException("REDEEM_UNAVAILABLE", "Redeem code is not available");
        }

        if (redeemCode.getBoundUserId() != null && !redeemCode.getBoundUserId().equals(userId)) {
            throw new BusinessException("REDEEM_BOUND", "Redeem code is bound to another user");
        }

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found: " + userId));

        Map<String, Object> result;
        if (redeemCode.isBalance()) {
            result = redeemBalance(redeemCode, user);
        } else if (redeemCode.isSubscription()) {
            result = redeemSubscription(redeemCode, user);
        } else {
            throw new BusinessException("REDEEM_TYPE", "Unknown redeem code type");
        }

        redeemCode.setUsedCount(redeemCode.getUsedCount() + 1);
        redeemCodeRepository.save(redeemCode);

        log.info("Redeem code used: code={}, user_id={}", code, userId);
        return result;
    }

    private Map<String, Object> redeemBalance(RedeemCodeEntity code, UserEntity user) {
        BigDecimal amount = code.getAmount() != null ? code.getAmount() : BigDecimal.ZERO;
        user.setBalance(user.getBalance().add(amount));
        user.setTotalRecharged(user.getTotalRecharged().add(amount));
        userRepository.save(user);

        log.info("Balance redeemed: user_id={}, amount={}, new_balance={}", user.getId(), amount, user.getBalance());
        return Map.of("type", "balance", "amount", amount, "new_balance", user.getBalance(),
                "message", "Successfully redeemed $" + amount + " to your balance");
    }

    private Map<String, Object> redeemSubscription(RedeemCodeEntity code, UserEntity user) {
        log.info("Subscription redeemed: user_id={}, group_id={}, days={}", user.getId(), code.getGroupId(), code.getSubscriptionDays());
        return Map.of("type", "subscription", "group_id", code.getGroupId(), "days", code.getSubscriptionDays(),
                "message", "Successfully redeemed subscription");
    }
}
