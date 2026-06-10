package com.landgate.trigger.http.checkin;

import com.landgate.api.checkin.dto.CheckinRecordDTO;
import com.landgate.api.checkin.dto.CheckinResultDTO;
import com.landgate.api.checkin.dto.CheckinStatusDTO;
import com.landgate.api.checkin.dto.RewardRuleDTO;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.checkin.model.entity.CheckinResultEntity;
import com.landgate.domain.checkin.model.entity.CheckinRewardRuleEntity;
import com.landgate.domain.checkin.model.entity.CheckinStatusEntity;
import com.landgate.domain.checkin.model.entity.UserCheckinEntity;
import com.landgate.domain.checkin.service.CheckinDomainService;
import com.landgate.types.response.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 签到控制器 —— 提供用户每日签到、签到状态和签到记录查询接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/checkin")
@RequiredArgsConstructor
public class CheckinController {

    private final CheckinDomainService checkinDomainService;

    /** 查询当前用户今日签到状态。 */
    @GetMapping("/status")
    public ResponseEntity<?> status(HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        if (userId == null || userId == 0L) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        return ResponseEntity.ok(toStatusDTO(checkinDomainService.getStatus(userId)));
    }

    /** 执行当前用户今日签到。 */
    @PostMapping
    public ResponseEntity<?> checkin(HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        if (userId == null || userId == 0L) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        CheckinResultDTO result = toResultDTO(checkinDomainService.checkin(userId));
        return ResponseEntity.ok(result);
    }

    /** 分页查询当前用户签到记录。 */
    @GetMapping("/records")
    public ResponseEntity<?> records(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size,
                                     HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        if (userId == null || userId == 0L) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        List<CheckinRecordDTO> records = checkinDomainService.listRecords(userId, normalizedPage, normalizedSize)
                .stream()
                .map(this::toRecordDTO)
                .toList();
        return ResponseEntity.ok(PageResponse.of(
                records,
                normalizedPage,
                normalizedSize,
                checkinDomainService.countRecords(userId)
        ));
    }

    private CheckinStatusDTO toStatusDTO(CheckinStatusEntity entity) {
        return new CheckinStatusDTO(
                entity.today(),
                entity.signedToday(),
                entity.canCheckin(),
                entity.todayStatus(),
                entity.streakDays(),
                entity.todayReward(),
                entity.nextReward(),
                entity.rewardRules().stream().map(this::toRewardRuleDTO).toList(),
                entity.todayRecord() != null ? toRecordDTO(entity.todayRecord()) : null
        );
    }

    private CheckinResultDTO toResultDTO(CheckinResultEntity entity) {
        return new CheckinResultDTO(entity.alreadySigned(), toRecordDTO(entity.record()));
    }

    private RewardRuleDTO toRewardRuleDTO(CheckinRewardRuleEntity entity) {
        return new RewardRuleDTO(entity.day(), entity.reward());
    }

    private CheckinRecordDTO toRecordDTO(UserCheckinEntity entity) {
        return new CheckinRecordDTO(
                entity.getId(),
                entity.getSignDate(),
                entity.getStreakDays(),
                entity.getRewardAmount(),
                entity.getStatus() != null ? entity.getStatus().name() : null,
                entity.getBalanceTransactionId(),
                entity.getCreatedAt()
        );
    }

    private Long getUserIdFromRequest(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("user_id");
        if (userId != null) return userId;

        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof UserEntity user) {
            return user.getId();
        }
        return 0L;
    }
}
