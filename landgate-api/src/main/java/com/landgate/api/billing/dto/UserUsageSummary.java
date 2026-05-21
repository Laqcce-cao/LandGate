package com.landgate.api.billing.dto;

import java.math.BigDecimal;

/**
 * 按用户聚合的用量汇总 —— 用于排行榜等视图。
 * <p>
 * 包含指定时间窗口内每个用户的总费用、总 Token 数和调用次数。
 */
public record UserUsageSummary(
        Long userId,
        String username,
        String email,
        BigDecimal totalCost,
        Long totalTokens,
        Long callCount
) {}
