package com.landgate.trigger.http.admin;

import com.landgate.api.balance.dto.AdminBalanceTransactionDTO;
import com.landgate.domain.balance.model.entity.AdminBalanceTransactionEntity;
import com.landgate.domain.balance.service.BalanceTransactionDomainService;
import com.landgate.types.enums.BalanceFundingType;
import com.landgate.types.enums.BalanceTransactionStatus;
import com.landgate.types.enums.BalanceTransactionType;
import com.landgate.types.exception.BusinessException;
import com.landgate.types.response.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理员余额流水控制器 —— 查询全站用户余额变动和真实收款信息。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/balance-transactions")
@RequiredArgsConstructor
public class BalanceTransactionAdminController {

    private final BalanceTransactionDomainService balanceTransactionDomainService;

    /**
     * 分页查询全站余额流水，支持关键词、业务类型、资金性质和状态筛选。
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String transactionType,
                                  @RequestParam(required = false) String fundingType,
                                  @RequestParam(required = false) String status) {
        BalanceTransactionType txType = parseEnum(BalanceTransactionType.class, transactionType, "余额变动业务类型");
        BalanceFundingType fundType = parseEnum(BalanceFundingType.class, fundingType, "资金性质");
        BalanceTransactionStatus txStatus = parseEnum(BalanceTransactionStatus.class, status, "处理状态");
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = Math.min(Math.max(size, 1), 100);

        List<AdminBalanceTransactionDTO> items = balanceTransactionDomainService
                .listAdminTransactions(keyword, txType, fundType, txStatus, normalizedPage, normalizedSize)
                .stream()
                .map(this::toDTO)
                .toList();
        long total = balanceTransactionDomainService.countAdminTransactions(keyword, txType, fundType, txStatus);
        log.debug("Admin list balance transactions: page={}, size={}, keyword={}, transactionType={}, fundingType={}, status={}",
                normalizedPage, normalizedSize, keyword, transactionType, fundingType, status);
        return ResponseEntity.ok(PageResponse.of(items, normalizedPage, normalizedSize, total));
    }

    private AdminBalanceTransactionDTO toDTO(AdminBalanceTransactionEntity entity) {
        return new AdminBalanceTransactionDTO(
                entity.getId(),
                entity.getUserId(),
                entity.getUserEmail(),
                entity.getTransactionType() != null ? entity.getTransactionType().name() : null,
                entity.getFundingType() != null ? entity.getFundingType().name() : null,
                entity.getAmount(),
                entity.getCashIncomeAmount(),
                entity.getBalanceBefore(),
                entity.getBalanceAfter(),
                entity.getOperatorType(),
                entity.getOperatorId(),
                entity.getRemark(),
                entity.getStatus() != null ? entity.getStatus().name() : null,
                entity.getFailureReason(),
                entity.getCompletedAt(),
                entity.getCreatedAt()
        );
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumClass, String value, String label) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(enumClass, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_BALANCE_TRANSACTION_FILTER", label + "筛选值无效");
        }
    }
}
