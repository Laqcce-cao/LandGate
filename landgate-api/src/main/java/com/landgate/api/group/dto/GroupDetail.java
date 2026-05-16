package com.landgate.api.group.dto;

import com.landgate.types.enums.Platform;
import com.landgate.types.enums.Status;
import com.landgate.types.enums.SubscriptionType;
import lombok.Builder;
import java.math.BigDecimal;
import java.time.Instant;

@Builder
public record GroupDetail(
        Long id, String name, Platform platform,
        SubscriptionType subscriptionType, Status status,
        BigDecimal rateMultiplier, Boolean isExclusive,
        BigDecimal dailyLimitUsd, BigDecimal monthlyLimitUsd,
        Integer rpmLimit, Instant createdAt
) {
    @Builder
    public record AccountBinding(Long accountId, String accountName, Integer priority) {}
    @Builder
    public record UserAuthorization(Long userId, String userEmail) {}
}
