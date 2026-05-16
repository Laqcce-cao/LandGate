package com.landgate.api.marketing.dto;

public record PromoCodeUpdateRequest(
        Boolean enabled, Integer maxUses, String expiresAt, String notes
) {}
