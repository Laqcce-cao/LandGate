package com.landgate.api.group.dto;

import java.math.BigDecimal;

public record GroupUpdateRequest(
        String name, String subscriptionType,
        String description, BigDecimal rateMultiplier,
        Boolean isExclusive, Integer rpmLimit
) {}
