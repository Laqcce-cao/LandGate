package com.landgate.api.marketing.dto;

public record RedeemCodeUpdateRequest(
        Boolean enabled, Integer maxUses, String expiresAt, String notes
) {}
