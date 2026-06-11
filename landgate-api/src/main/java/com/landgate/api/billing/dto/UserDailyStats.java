package com.landgate.api.billing.dto;

import java.time.LocalDate;

public record UserDailyStats(
        Long userId,
        LocalDate date,
        Long totalTokens
) {}
