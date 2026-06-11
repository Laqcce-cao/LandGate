package com.landgate.domain.checkin.service;

import com.landgate.domain.balance.model.entity.BalanceTransactionEntity;
import com.landgate.domain.balance.model.valobj.BalanceTransactionCommand;
import com.landgate.domain.balance.service.BalanceTransactionDomainService;
import com.landgate.domain.checkin.adapter.repository.IUserCheckinRepository;
import com.landgate.domain.checkin.model.entity.CheckinResultEntity;
import com.landgate.domain.checkin.model.entity.CheckinRewardRuleEntity;
import com.landgate.domain.checkin.model.entity.CheckinStatusEntity;
import com.landgate.domain.checkin.model.entity.UserCheckinEntity;
import com.landgate.types.enums.BalanceFundingType;
import com.landgate.types.enums.BalanceTransactionType;
import com.landgate.types.enums.CheckinStatus;
import com.landgate.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 签到领域服务 —— 处理每日签到、连续天数计算和签到奖励发放。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckinDomainService {

    private static final ZoneId CHECKIN_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<BigDecimal> REWARDS = List.of(
            new BigDecimal("0.5"),
            new BigDecimal("0.5"),
            new BigDecimal("0.5"),
            new BigDecimal("0.5"),
            new BigDecimal("0.5"),
            new BigDecimal("0.5"),
            new BigDecimal("0.5")
    );

    private final IUserCheckinRepository checkinRepository;
    private final BalanceTransactionDomainService balanceTransactionDomainService;

    /**
     * 查询用户今日签到状态和奖励规则。
     */
    public CheckinStatusEntity getStatus(Long userId) {
        LocalDate today = today();
        UserCheckinEntity todayRecord = checkinRepository.findByUserIdAndSignDate(userId, today).orElse(null);
        UserCheckinEntity latest = todayRecord != null ? todayRecord : checkinRepository.findLatestByUserId(userId).orElse(null);

        int displayStreak = currentDisplayStreak(latest, today);
        int expectedStreak = expectedStreakForToday(latest, today);
        BigDecimal todayReward = todayRecord != null ? todayRecord.getRewardAmount() : rewardForStreak(expectedStreak);
        BigDecimal nextReward = rewardForStreak(Math.min(expectedStreak + 1, Integer.MAX_VALUE));
        String todayStatus = todayRecord != null ? todayRecord.getStatus().name() : "NONE";
        boolean canCheckin = todayRecord == null || todayRecord.getStatus() == CheckinStatus.FAILED;

        return new CheckinStatusEntity(
                today,
                todayRecord != null,
                canCheckin,
                todayStatus,
                displayStreak,
                todayReward,
                nextReward,
                rewardRules(),
                todayRecord
        );
    }

    /**
     * 执行今日签到。今日已完成则幂等返回，失败记录允许重试。
     */
    public CheckinResultEntity checkin(Long userId) {
        LocalDate today = today();
        UserCheckinEntity record = checkinRepository.findByUserIdAndSignDate(userId, today).orElse(null);
        if (record != null) {
            if (record.getStatus() == CheckinStatus.COMPLETED) {
                return new CheckinResultEntity(true, record);
            }
            if (record.getStatus() == CheckinStatus.PENDING) {
                throw new BusinessException("CHECKIN_PENDING", "签到奖励正在发放中，请稍后重试");
            }
            checkinRepository.markPending(record.getId());
            record.setStatus(CheckinStatus.PENDING);
            record.setFailureReason(null);
            return grantReward(record, false);
        }

        UserCheckinEntity latest = checkinRepository.findLatestByUserId(userId).orElse(null);
        int streakDays = expectedStreakForToday(latest, today);
        UserCheckinEntity created = UserCheckinEntity.builder()
                .userId(userId)
                .signDate(today)
                .streakDays(streakDays)
                .rewardAmount(rewardForStreak(streakDays))
                .status(CheckinStatus.PENDING)
                .build();
        try {
            created = checkinRepository.save(created);
        } catch (DuplicateKeyException e) {
            UserCheckinEntity concurrent = checkinRepository.findByUserIdAndSignDate(userId, today)
                    .orElseThrow(() -> e);
            return handleConcurrentRecord(concurrent);
        }
        return grantReward(created, false);
    }

    /**
     * 分页查询用户签到记录。
     */
    public List<UserCheckinEntity> listRecords(Long userId, int page, int size) {
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        return checkinRepository.listByUserId(userId, normalizedPage * normalizedSize, normalizedSize);
    }

    public long countRecords(Long userId) {
        return checkinRepository.countByUserId(userId);
    }

    private CheckinResultEntity handleConcurrentRecord(UserCheckinEntity record) {
        if (record.getStatus() == CheckinStatus.COMPLETED) {
            return new CheckinResultEntity(true, record);
        }
        if (record.getStatus() == CheckinStatus.PENDING) {
            throw new BusinessException("CHECKIN_PENDING", "签到奖励正在发放中，请稍后重试");
        }
        checkinRepository.markPending(record.getId());
        record.setStatus(CheckinStatus.PENDING);
        record.setFailureReason(null);
        return grantReward(record, false);
    }

    private CheckinResultEntity grantReward(UserCheckinEntity record, boolean alreadySigned) {
        try {
            BalanceTransactionEntity tx = balanceTransactionDomainService.apply(new BalanceTransactionCommand(
                    record.getUserId(),
                    BalanceTransactionType.CHECKIN_REWARD,
                    BalanceFundingType.GIFT,
                    record.getRewardAmount(),
                    BigDecimal.ZERO,
                    "CHECKIN",
                    String.valueOf(record.getId()),
                    "SYSTEM",
                    "system",
                    "每日签到奖励",
                    "{\"signDate\":\"" + record.getSignDate() + "\",\"streakDays\":" + record.getStreakDays() + "}",
                    false
            ));
            checkinRepository.markCompleted(record.getId(), tx.getId());
            record.setBalanceTransactionId(tx.getId());
            record.setStatus(CheckinStatus.COMPLETED);
            record.setFailureReason(null);
            return new CheckinResultEntity(alreadySigned, record);
        } catch (BusinessException e) {
            if ("BALANCE_TRANSACTION_PENDING".equals(e.getErrorCode())) {
                checkinRepository.markPending(record.getId());
                record.setStatus(CheckinStatus.PENDING);
                record.setFailureReason(null);
                throw new BusinessException("CHECKIN_PENDING", "签到奖励正在发放中，请稍后重试");
            }
            checkinRepository.markFailed(record.getId(), e.getMessage());
            record.setStatus(CheckinStatus.FAILED);
            record.setFailureReason(e.getMessage());
            throw e;
        }
    }

    private int currentDisplayStreak(UserCheckinEntity latest, LocalDate today) {
        if (latest == null) return 0;
        if (latest.getSignDate().equals(today) || latest.getSignDate().equals(today.minusDays(1))) {
            return latest.getStreakDays();
        }
        return 0;
    }

    private int expectedStreakForToday(UserCheckinEntity latest, LocalDate today) {
        if (latest == null) return 1;
        if (latest.getSignDate().equals(today)) return latest.getStreakDays();
        if (latest.getSignDate().equals(today.minusDays(1))) return latest.getStreakDays() + 1;
        return 1;
    }

    private BigDecimal rewardForStreak(int streakDays) {
        int index = Math.min(Math.max(streakDays, 1), REWARDS.size()) - 1;
        return REWARDS.get(index);
    }

    private List<CheckinRewardRuleEntity> rewardRules() {
        return java.util.stream.IntStream.range(0, REWARDS.size())
                .mapToObj(i -> new CheckinRewardRuleEntity(i + 1, REWARDS.get(i)))
                .toList();
    }

    private LocalDate today() {
        return LocalDate.now(CHECKIN_ZONE);
    }
}
