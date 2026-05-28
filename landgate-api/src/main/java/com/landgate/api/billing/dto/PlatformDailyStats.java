package com.landgate.api.billing.dto;

import java.time.LocalDate;

public record PlatformDailyStats(
        LocalDate date,
        Long inputTokens,
        Long outputTokens,
        Long cacheReadTokens,
        Long cacheCreationTokens,
        Long callCount
) {}
