package com.landgate.domain.billing.model.entity;

import com.landgate.types.enums.Platform;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class ModelPriceEntity {

    private Long id;
    private String model;
    private Platform platform;

    @Builder.Default
    private BigDecimal inputPrice = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal outputPrice = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal cacheWritePrice = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal cacheReadPrice = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal cacheWrite5mPrice = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal cacheWrite1hPrice = BigDecimal.ZERO;
    @Builder.Default
    private Boolean supportsCacheBreakdown = false;

    @Builder.Default
    private Boolean enabled = true;
    private String notes;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

}
