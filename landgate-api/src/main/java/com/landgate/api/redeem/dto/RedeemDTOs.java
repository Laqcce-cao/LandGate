package com.landgate.api.redeem.dto;

import java.math.BigDecimal;

public final class RedeemDTOs {
    private RedeemDTOs() {}

    public record RedeemRequest(String code) {}
    public record ValidatePromoRequest(String code, BigDecimal amount) {}
}
