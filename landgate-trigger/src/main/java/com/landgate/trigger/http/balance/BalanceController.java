package com.landgate.trigger.http.balance;

import com.landgate.api.balance.dto.BalanceTransactionDTO;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.balance.model.entity.BalanceTransactionEntity;
import com.landgate.domain.balance.service.BalanceTransactionDomainService;
import com.landgate.types.response.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户余额控制器 —— 查询当前用户的低频余额变动明细。
 * <p>
 * API 高频消费仍由用量统计页面展示，不在余额明细中逐条展示。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/balance")
@RequiredArgsConstructor
public class BalanceController {

    private final BalanceTransactionDomainService balanceTransactionDomainService;

    /**
     * 查询当前登录用户的余额明细。
     */
    @GetMapping("/transactions")
    public ResponseEntity<?> listTransactions(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);
        if (userId == null || userId == 0L) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }

        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);
        List<BalanceTransactionDTO> items = balanceTransactionDomainService
                .listUserTransactions(userId, normalizedPage, normalizedSize)
                .stream()
                .map(this::toDTO)
                .toList();
        long total = balanceTransactionDomainService.countUserTransactions(userId);
        return ResponseEntity.ok(PageResponse.of(items, normalizedPage, normalizedSize, total));
    }

    private BalanceTransactionDTO toDTO(BalanceTransactionEntity entity) {
        return new BalanceTransactionDTO(
                entity.getId(),
                entity.getTransactionType() != null ? entity.getTransactionType().name() : null,
                entity.getFundingType() != null ? entity.getFundingType().name() : null,
                entity.getAmount(),
                entity.getBalanceAfter(),
                entity.getRemark(),
                entity.getStatus() != null ? entity.getStatus().name() : null,
                entity.getCompletedAt(),
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
