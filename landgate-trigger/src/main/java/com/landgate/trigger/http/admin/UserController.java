package com.landgate.trigger.http.admin;

import com.landgate.api.admin.dto.AdminDTOs.AdminBalanceAdjustmentRequest;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.auth.service.UserDomainService;
import com.landgate.domain.balance.model.entity.BalanceTransactionEntity;
import com.landgate.domain.balance.model.valobj.BalanceTransactionCommand;
import com.landgate.domain.balance.service.BalanceTransactionDomainService;
import com.landgate.types.enums.BalanceFundingType;
import com.landgate.types.enums.BalanceTransactionType;
import com.landgate.types.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户管理控制器 —— 管理员对用户的查询、编辑、状态变更接口。
 * <p>
 * 路由前缀：{@code /api/v1/admin/users}，需要管理员 JWT 认证。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class UserController {

    private final UserDomainService userDomainService;
    private final BalanceTransactionDomainService balanceTransactionDomainService;

    /**
     * 分页搜索用户列表。
     *
     * @param page     页码（0-based，默认 0）
     * @param pageSize 每页条数（默认 20）
     * @param search   搜索关键词（匹配用户名或邮箱），可选
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int pageSize,
                                  @RequestParam(required = false) String search) {
        log.debug("List users: page={}, pageSize={}, search={}", page, pageSize, search);
        String keyword = search != null ? search.trim() : "";
        List<UserEntity> users = userDomainService.listBySearch(keyword, page, pageSize);
        long total = userDomainService.countBySearch(keyword);
        return ResponseEntity.ok(Map.of("users", users, "total", total));
    }

    /**
     * 获取单个用户详情。
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        log.debug("Get user: id={}", id);
        UserEntity user = userDomainService.getById(id);
        return ResponseEntity.ok(user);
    }

    /**
     * 更新用户信息（仅更新非空字段）。
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody UserEntity updates) {
        log.info("Update user: id={}", id);
        UserEntity updated = userDomainService.update(id, updates);
        return ResponseEntity.ok(updated);
    }

    /**
     * 启用/禁用用户。
     */
    @PostMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        log.info("Update user status: id={}, status={}", id, status);
        userDomainService.updateStatus(id, status);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * 管理员调整用户余额，支持线下收款充值、赠送补偿和扣减余额。
     */
    @PostMapping("/{id}/balance-adjustments")
    public ResponseEntity<?> adjustBalance(@PathVariable Long id,
                                           @RequestBody AdminBalanceAdjustmentRequest req,
                                           HttpServletRequest request) {
        BalanceTransactionCommand command = buildAdminBalanceCommand(id, req, request);
        log.info("Admin adjust balance: user_id={}, kind={}, amount={}", id, req.kind(), req.amount());
        BalanceTransactionEntity transaction = balanceTransactionDomainService.apply(command);
        UserEntity user = userDomainService.getById(id);
        return ResponseEntity.ok(Map.of("user", user, "transaction", transaction));
    }

    /**
     * 兼容旧充值接口：按线下收款充值处理，不再写 payment_orders。
     */
    @PostMapping("/{id}/recharge")
    public ResponseEntity<?> recharge(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        BigDecimal amount = new BigDecimal(String.valueOf(body.get("amount")));
        AdminBalanceAdjustmentRequest req = new AdminBalanceAdjustmentRequest(
                "PAID", amount, amount, "管理员充值"
        );
        return adjustBalance(id, req, request);
    }

    private BalanceTransactionCommand buildAdminBalanceCommand(Long userId,
                                                               AdminBalanceAdjustmentRequest req,
                                                               HttpServletRequest request) {
        if (req.kind() == null || req.kind().isBlank()) {
            throw new BusinessException("INVALID_BALANCE_ADJUSTMENT_KIND", "余额调整类型不能为空");
        }
        if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("INVALID_BALANCE_AMOUNT", "余额调整金额必须大于 0");
        }
        if (req.remark() == null || req.remark().isBlank()) {
            throw new BusinessException("INVALID_BALANCE_REMARK", "余额调整备注不能为空");
        }

        String kind = req.kind().trim().toUpperCase();
        BalanceTransactionType transactionType;
        BalanceFundingType fundingType;
        BigDecimal amount = req.amount();
        BigDecimal cashIncomeAmount = BigDecimal.ZERO;
        boolean allowNegative = false;

        switch (kind) {
            case "PAID" -> {
                transactionType = BalanceTransactionType.ADMIN_RECHARGE;
                fundingType = BalanceFundingType.PAID;
                cashIncomeAmount = req.cashIncomeAmount() != null ? req.cashIncomeAmount() : req.amount();
                if (cashIncomeAmount.compareTo(BigDecimal.ZERO) < 0) {
                    throw new BusinessException("INVALID_CASH_INCOME", "真实收款金额不能小于 0");
                }
            }
            case "GIFT" -> {
                transactionType = BalanceTransactionType.ADMIN_GRANT;
                fundingType = BalanceFundingType.GIFT;
            }
            case "DEDUCT" -> {
                transactionType = BalanceTransactionType.ADMIN_DEDUCT;
                fundingType = BalanceFundingType.DEDUCT;
                amount = amount.negate();
            }
            default -> throw new BusinessException("INVALID_BALANCE_ADJUSTMENT_KIND", "余额调整类型无效");
        }

        String operatorId = String.valueOf(request.getAttribute("user_id"));
        String sourceId = "admin_balance_" + System.currentTimeMillis() + "_" + userId + "_" + UUID.randomUUID();
        return new BalanceTransactionCommand(
                userId,
                transactionType,
                fundingType,
                amount,
                cashIncomeAmount,
                "ADMIN_OPERATION",
                sourceId,
                "ADMIN",
                operatorId,
                req.remark().trim(),
                null,
                allowNegative
        );
    }
}
