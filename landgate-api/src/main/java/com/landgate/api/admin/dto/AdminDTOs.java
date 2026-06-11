package com.landgate.api.admin.dto;

import java.math.BigDecimal;

public final class AdminDTOs {
    private AdminDTOs() {}

    public record UpdateStatusRequest(String status, String errorMessage) {}
    public record SetSchedulableRequest(Boolean schedulable) {}
    public record BindAccountRequest(Long accountId, Integer priority) {}
    public record UpdatePriorityRequest(Integer priority) {}
    public record AllowUserRequest(Long userId) {}
    public record ConfirmRequest(String tradeNo, BigDecimal payAmount) {}
    public record AdminRefundRequest(String reason, Boolean forceRefund) {}
    public record AdminBalanceAdjustmentRequest(String kind, BigDecimal amount, BigDecimal cashIncomeAmount, String remark) {}
}
