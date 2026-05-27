package com.landgate.domain.billing.model.entity;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class ModelPriceEntity {

    private Long id;
    private String model;

    /** 计费模式：token（按 token）、per_request（按次）、image（按图片） */
    @Builder.Default
    private String billingMode = "token";

    /** model 字段是否为通配符模式（如 claude-opus-*） */
    @Builder.Default
    private Boolean wildcardMatch = false;

    /** 图片尺寸（image 模式专用）：1K、2K、4K */
    private String imageSize;
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
